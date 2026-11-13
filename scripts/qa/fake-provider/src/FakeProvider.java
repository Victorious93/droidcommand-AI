package fake.fleet;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;

/**
 * QA fixture, NOT shipped. Stands in for another fleet app's ContentProvider (VictorLaunch theme, VictorSuite My PC)
 * and misbehaves in a way chosen at BUILD time by the `mode` meta-data, so each failure shape is its own APK:
 * throw_iae, throw_ise, throw_security, null_cursor, empty, malformed, blob, slow, ok_theme.
 */
public class FakeProvider extends ContentProvider {
    private String mode = "empty";

    @Override public boolean onCreate() {
        try {
            ProviderInfo info = getContext().getPackageManager().getProviderInfo(
                new android.content.ComponentName(getContext(), FakeProvider.class), PackageManager.GET_META_DATA);
            mode = info.metaData.getString("mode", "empty");
        } catch (Exception e) { mode = "empty"; }
        return true;
    }

    @Override public Cursor query(Uri uri, String[] p, String s, String[] a, String o) {
        switch (mode) {
            case "throw_iae": throw new IllegalArgumentException("fake: unknown URI " + uri);
            case "throw_ise": throw new IllegalStateException("fake: provider broken");
            case "throw_security": throw new SecurityException("fake: caller not allowed");
            case "null_cursor": return null;
            case "slow":
                try { Thread.sleep(20000); } catch (InterruptedException ignored) { }
                return new MatrixCursor(new String[] {"schemaVersion"});
            case "malformed": {
                MatrixCursor c = new MatrixCursor(new String[] {"schemaVersion", "primary", "pc_name", "host", "port", "profile_id"});
                c.addRow(new Object[] {"not-a-number", "####", null, 12345678901234L, "port-x", ""});
                return c;
            }
            case "blob": {
                MatrixCursor c = new MatrixCursor(new String[] {"schemaVersion", "primary"});
                c.addRow(new Object[] {new byte[] {1, 2, 3}, new byte[64 * 1024]});
                return c;
            }
            case "ok_theme": {
                MatrixCursor c = new MatrixCursor(new String[] {"schemaVersion"});
                c.addRow(new Object[] {1});
                return c;
            }
            default: return new MatrixCursor(new String[] {"schemaVersion"});
        }
    }

    @Override public Bundle call(String method, String arg, Bundle extras) {
        if ("throw_iae".equals(mode)) throw new IllegalArgumentException("fake: call");
        if ("throw_ise".equals(mode)) throw new IllegalStateException("fake: call");
        return null;
    }

    @Override public String getType(Uri u) { return null; }
    @Override public Uri insert(Uri u, ContentValues v) { return null; }
    @Override public int delete(Uri u, String s, String[] a) { return 0; }
    @Override public int update(Uri u, ContentValues v, String s, String[] a) { return 0; }
}
