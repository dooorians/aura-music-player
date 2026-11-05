// Host harness for the playlist assistant's engine: the same prompt file and grammar the app ships, run against a
// GGUF model on a workstation.
//
//   slm_cli <model.gguf> <playlist_intent.gbnf> <playlist_intent_prompt.txt> <request> [prefix-cache-file]
//
// The prompt file is the app's (app/src/main/assets/slm/): "### system", "### user" and "### assistant" headers, each
// followed by the message. The request is appended as the last user message.
#include "slm_engine.h"

#include <cstdio>
#include <fstream>
#include <sstream>
#include <string>

namespace {

std::string read_file(const char * path) {
    std::ifstream in(path, std::ios::binary);
    std::stringstream ss;
    ss << in.rdbuf();
    return ss.str();
}

std::string trim(const std::string & s) {
    const auto b = s.find_first_not_of(" \t\r\n");
    if (b == std::string::npos) return {};
    const auto e = s.find_last_not_of(" \t\r\n");
    return s.substr(b, e - b + 1);
}

std::vector<aura::slm::ChatMessage> parse_prompt(const std::string & text) {
    std::vector<aura::slm::ChatMessage> out;
    std::istringstream in(text);
    std::string line, role, body;
    auto flush = [&] {
        if (!role.empty()) out.push_back({role, trim(body)});
        body.clear();
    };
    while (std::getline(in, line)) {
        if (line.rfind("### ", 0) == 0) {
            flush();
            role = trim(line.substr(4));
        } else if (!role.empty()) {
            body += line;
            body += '\n';
        }
    }
    flush();
    return out;
}

}  // namespace

int main(int argc, char ** argv) {
    if (argc < 5) {
        std::fprintf(stderr, "usage: %s model.gguf grammar.gbnf prompt.txt \"request\" [prefix-cache]\n", argv[0]);
        return 2;
    }
    aura::slm::GenerateParams params;
    params.model_path = argv[1];
    params.grammar = read_file(argv[2]);
    params.messages = parse_prompt(read_file(argv[3]));
    params.messages.push_back({"user", argv[4]});
    if (argc > 5) params.prefix_cache_path = argv[5];

    const auto r = aura::slm::generate(params, nullptr);
    std::fprintf(stderr, "ok=%d cache_hit=%d prompt=%d tok gen=%d tok load=%.0fms prompt=%.0fms gen=%.0fms %s\n", r.ok,
                 r.prefix_cache_hit, r.prompt_tokens, r.generated_tokens, r.load_ms, r.prompt_ms, r.generate_ms, r.error.c_str());
    std::printf("%s\n", r.text.c_str());
    return r.ok ? 0 : 1;
}
