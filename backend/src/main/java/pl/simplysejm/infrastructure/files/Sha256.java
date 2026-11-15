package pl.simplysejm.infrastructure.files;

import java.security.*;
import java.util.HexFormat;

public final class Sha256 {
    private Sha256() {}
    public static String digest(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
