package android.net;

import android.os.Parcel;

public class TestUri extends Uri {
    public static final TestUri INSTANCE = new TestUri();

    private TestUri() {}

    @Override
    public boolean isHierarchical() { return false; }

    @Override
    public boolean isRelative() { return false; }

    @Override
    public String getScheme() { return "content"; }

    @Override
    public String getSchemeSpecificPart() { return "//media/1"; }

    @Override
    public String getEncodedSchemeSpecificPart() { return "//media/1"; }

    @Override
    public String getAuthority() { return "media"; }

    @Override
    public String getEncodedAuthority() { return "media"; }

    @Override
    public String getUserInfo() { return null; }

    @Override
    public String getEncodedUserInfo() { return null; }

    @Override
    public String getHost() { return "media"; }

    @Override
    public int getPort() { return -1; }

    @Override
    public String getPath() { return "/1"; }

    @Override
    public String getEncodedPath() { return "/1"; }

    @Override
    public String getQuery() { return null; }

    @Override
    public String getEncodedQuery() { return null; }

    @Override
    public String getFragment() { return null; }

    @Override
    public String getEncodedFragment() { return null; }

    @Override
    public java.util.List<String> getPathSegments() { return java.util.Collections.singletonList("1"); }

    @Override
    public String getLastPathSegment() { return "1"; }

    @Override
    public Builder buildUpon() { return null; }

    @Override
    public int compareTo(Uri other) { return 0; }

    @Override
    public String toString() { return "content://media/1"; }

    @Override
    public int describeContents() { return 0; }

    @Override
    public void writeToParcel(Parcel dest, int flags) {}
}
