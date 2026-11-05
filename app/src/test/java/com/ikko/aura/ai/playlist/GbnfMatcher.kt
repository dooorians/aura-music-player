package com.ikko.aura.ai.playlist

/**
 * A small GBNF recogniser for the tests: enough of llama.cpp's grammar language to decide whether a string is in the
 * language of playlist_intent.gbnf (rules, "literals" with \" and \\ escapes, [classes] with ranges, ( groups ),
 * alternatives, ? * + {m} {m,} {m,n}, # comments). It answers "is this text one the sampler could have produced", so the
 * grammar the app ships is checked by `./gradlew test`, without a model.
 */
class GbnfMatcher(source: String) {

    private sealed interface Node
    private data class Lit(val text: String) : Node
    private data class Cls(val ranges: List<CharRange>, val negated: Boolean) : Node
    private data class Ref(val name: String) : Node
    private data class Seq(val items: List<Node>) : Node
    private data class Alt(val options: List<Node>) : Node
    private data class Rep(val item: Node, val min: Int, val max: Int) : Node

    private val rules: Map<String, Node> = parseRules(stripComments(source))

    fun matches(input: String, root: String = "root"): Boolean {
        val memo = HashMap<Pair<String, Int>, Set<Int>>()
        return input.length in ends(Ref(root), input, 0, memo)
    }

    /** A random string of the language (repetitions without an upper bound stop at min + 3). */
    fun sample(random: kotlin.random.Random, root: String = "root"): String = buildString { sample(Ref(root), random, this) }

    private fun sample(node: Node, random: kotlin.random.Random, out: StringBuilder) {
        when (node) {
            is Lit -> out.append(node.text)
            is Cls -> {
                require(!node.negated) { "cannot sample a negated class" }
                val r = node.ranges[random.nextInt(node.ranges.size)]
                out.append((r.first.code + random.nextInt(r.last.code - r.first.code + 1)).toChar())
            }
            is Ref -> sample(rules.getValue(node.name), random, out)
            is Seq -> node.items.forEach { sample(it, random, out) }
            is Alt -> sample(node.options[random.nextInt(node.options.size)], random, out)
            is Rep -> repeat(node.min + random.nextInt((if (node.max < 0) node.min + 3 else node.max) - node.min + 1)) { sample(node.item, random, out) }
        }
    }

    private fun ends(node: Node, s: String, pos: Int, memo: HashMap<Pair<String, Int>, Set<Int>>): Set<Int> = when (node) {
        is Lit -> if (s.startsWith(node.text, pos)) setOf(pos + node.text.length) else emptySet()
        is Cls -> if (pos < s.length && (node.ranges.any { s[pos] in it } != node.negated)) setOf(pos + 1) else emptySet()
        is Ref -> memo.getOrPut(node.name to pos) {
            ends(rules[node.name] ?: error("undefined rule ${node.name}"), s, pos, memo)
        }
        is Seq -> node.items.fold(setOf(pos)) { acc, item -> acc.flatMapTo(HashSet()) { ends(item, s, it, memo) } }
        is Alt -> node.options.flatMapTo(HashSet()) { ends(it, s, pos, memo) }
        is Rep -> {
            // Bounded even when unbounded: no item of these grammars matches the empty string, so a repetition can
            // never need more steps than there are characters left.
            val limit = if (node.max < 0) s.length - pos + 1 else node.max
            val out = HashSet<Int>()
            if (node.min == 0) out += pos
            var frontier = setOf(pos)
            var count = 0
            while (count < limit && frontier.isNotEmpty()) {
                frontier = frontier.flatMapTo(HashSet()) { ends(node.item, s, it, memo) }
                count++
                if (count >= node.min) out += frontier
            }
            out
        }
    }

    // --- parsing -----------------------------------------------------------------------------------------------------

    private fun stripComments(text: String): String = text.lineSequence().joinToString("\n") { line ->
        var inQuote = false
        var inClass = false
        var i = 0
        var cut = line.length
        while (i < line.length) {
            val c = line[i]
            when {
                c == '\\' -> i++
                inQuote -> if (c == '"') inQuote = false
                inClass -> if (c == ']') inClass = false
                c == '"' -> inQuote = true
                c == '[' -> inClass = true
                c == '#' -> { cut = i; break }
            }
            i++
        }
        line.substring(0, cut)
    }

    private fun parseRules(text: String): Map<String, Node> {
        val defs = Regex("""(?m)^\s*([a-zA-Z0-9_-]+)\s*::=""").findAll(text).toList()
        require(defs.isNotEmpty()) { "no rule" }
        return defs.mapIndexed { i, m ->
            val end = if (i + 1 < defs.size) defs[i + 1].range.first else text.length
            val body = text.substring(m.range.last + 1, end)
            m.groupValues[1] to Parser(body).parseAll()
        }.toMap()
    }

    private class Parser(val s: String) {
        var i = 0

        fun parseAll(): Node {
            val n = alt()
            skip()
            require(i >= s.length) { "unexpected '${s.substring(i)}'" }
            return n
        }

        private fun skip() { while (i < s.length && s[i].isWhitespace()) i++ }

        private fun alt(): Node {
            val options = mutableListOf(seq())
            skip()
            while (i < s.length && s[i] == '|') { i++; options += seq(); skip() }
            return if (options.size == 1) options[0] else Alt(options)
        }

        private fun seq(): Node {
            val items = mutableListOf<Node>()
            while (true) {
                skip()
                if (i >= s.length || s[i] == '|' || s[i] == ')') break
                items += postfix(atom())
            }
            return if (items.size == 1) items[0] else Seq(items)
        }

        private fun postfix(base: Node): Node {
            var n = base
            while (i < s.length) {
                n = when (s[i]) {
                    '?' -> { i++; Rep(n, 0, 1) }
                    '*' -> { i++; Rep(n, 0, -1) }
                    '+' -> { i++; Rep(n, 1, -1) }
                    '{' -> {
                        val close = s.indexOf('}', i)
                        val parts = s.substring(i + 1, close).split(',').map { it.trim() }
                        i = close + 1
                        val min = parts[0].toInt()
                        val max = if (parts.size == 1) min else parts[1].ifEmpty { "-1" }.toInt()
                        Rep(n, min, max)
                    }
                    else -> return n
                }
            }
            return n
        }

        private fun atom(): Node {
            skip()
            return when (s[i]) {
                '"' -> {
                    i++
                    val sb = StringBuilder()
                    while (s[i] != '"') {
                        if (s[i] == '\\') { i++; sb.append(escape(s[i])) } else sb.append(s[i])
                        i++
                    }
                    i++
                    Lit(sb.toString())
                }
                '[' -> {
                    i++
                    val negated = s[i] == '^'
                    if (negated) i++
                    val ranges = mutableListOf<CharRange>()
                    while (s[i] != ']') {
                        val a = if (s[i] == '\\') { i++; escape(s[i]) } else s[i]
                        i++
                        if (s[i] == '-' && s[i + 1] != ']') {
                            i++
                            val b = if (s[i] == '\\') { i++; escape(s[i]) } else s[i]
                            i++
                            ranges += a..b
                        } else {
                            ranges += a..a
                        }
                    }
                    i++
                    Cls(ranges, negated)
                }
                '(' -> {
                    i++
                    val n = alt()
                    skip()
                    require(s[i] == ')') { "missing )" }
                    i++
                    n
                }
                else -> {
                    val start = i
                    while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '-' || s[i] == '_')) i++
                    require(i > start) { "unexpected '${s[i]}'" }
                    Ref(s.substring(start, i))
                }
            }
        }

        private fun escape(c: Char): Char = when (c) {
            'n' -> '\n'
            't' -> '\t'
            'r' -> '\r'
            else -> c
        }
    }
}
