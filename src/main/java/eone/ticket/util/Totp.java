package eone.ticket.util;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * TOTP (RFC 6238) con i parametri standard compatibili con Google/Microsoft
 * Authenticator, FreeOTP, ecc.: HMAC-SHA1, 6 cifre, periodo 30 secondi.
 * Nessuna dipendenza esterna.
 */
public final class Totp {

    public static final int PERIODO_SEC = 30;
    public static final int CIFRE       = 6;
    /** Tolleranza ±1 periodo (±30 s) per sfasamenti d'orologio del telefono. */
    public static final int FINESTRA    = 1;

    private static final SecureRandom RNG = new SecureRandom();
    private static final char[] B32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".toCharArray();

    private Totp() { }

    /** Nuovo segreto casuale di 160 bit, in Base32 (32 caratteri). */
    public static String nuovoSegreto() {
        byte[] b = new byte[20];
        RNG.nextBytes(b);
        return base32Encode(b);
    }

    /** Periodo corrente (numero di intervalli da 30 s dall'epoch). */
    public static long stepCorrente() {
        return System.currentTimeMillis() / 1000L / PERIODO_SEC;
    }

    /**
     * Verifica un codice. Restituisce il periodo (step) a cui corrisponde,
     * oppure -1 se non valido. Il chiamante deve rifiutare uno step già
     * usato (≤ ultimo step accettato) per impedire il riuso dello stesso
     * codice (replay).
     */
    public static long verifica(String segretoBase32, String codice, long stepRiferimento) {
        if (segretoBase32 == null || codice == null) return -1;
        String c = codice.replace(" ", "").trim();
        if (!c.matches("\\d{" + CIFRE + "}")) return -1;
        byte[] key = base32Decode(segretoBase32);
        for (int d = -FINESTRA; d <= FINESTRA; d++) {
            long step = stepRiferimento + d;
            if (constantTimeEquals(codice(key, step), c)) return step;
        }
        return -1;
    }

    /** Codice per un dato step (pubblico per i test). */
    public static String codice(byte[] key, long step) {
        try {
            byte[] msg = new byte[8];
            for (int i = 7; i >= 0; i--) { msg[i] = (byte) (step & 0xFF); step >>>= 8; }
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            byte[] h = mac.doFinal(msg);
            int off = h[h.length - 1] & 0x0F;
            int bin = ((h[off] & 0x7F) << 24) | ((h[off + 1] & 0xFF) << 16)
                    | ((h[off + 2] & 0xFF) << 8) | (h[off + 3] & 0xFF);
            int otp = bin % (int) Math.pow(10, CIFRE);
            return String.format("%0" + CIFRE + "d", otp);
        } catch (Exception e) {
            throw new IllegalStateException("HmacSHA1 non disponibile", e);
        }
    }

    /** URI otpauth:// da codificare nel QR (formato Key Uri di Google Authenticator). */
    public static String otpauthUri(String issuer, String account, String segretoBase32) {
        String label = enc(issuer) + ":" + enc(account);
        return "otpauth://totp/" + label + "?secret=" + segretoBase32 + "&issuer=" + enc(issuer)
             + "&algorithm=SHA1&digits=" + CIFRE + "&period=" + PERIODO_SEC;
    }

    /** Segreto raggruppato a 4 caratteri, per l'inserimento manuale nell'app. */
    public static String formattaPerUtente(String segretoBase32) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < segretoBase32.length(); i++) {
            if (i > 0 && i % 4 == 0) sb.append(' ');
            sb.append(segretoBase32.charAt(i));
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a.length() != b.length()) return false;
        int r = 0;
        for (int i = 0; i < a.length(); i++) r |= a.charAt(i) ^ b.charAt(i);
        return r == 0;
    }

    public static String base32Encode(byte[] data) {
        StringBuilder sb = new StringBuilder();
        int buffer = 0, bits = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xFF);
            bits += 8;
            while (bits >= 5) {
                sb.append(B32[(buffer >> (bits - 5)) & 0x1F]);
                bits -= 5;
            }
        }
        if (bits > 0) sb.append(B32[(buffer << (5 - bits)) & 0x1F]);
        return sb.toString();
    }

    public static byte[] base32Decode(String s) {
        String in = s.replace(" ", "").replace("=", "").toUpperCase();
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int buffer = 0, bits = 0;
        for (char c : in.toCharArray()) {
            int v;
            if (c >= 'A' && c <= 'Z') v = c - 'A';
            else if (c >= '2' && c <= '7') v = c - '2' + 26;
            else throw new IllegalArgumentException("Carattere Base32 non valido: " + c);
            buffer = (buffer << 5) | v;
            bits += 5;
            if (bits >= 8) {
                out.write((buffer >> (bits - 8)) & 0xFF);
                bits -= 8;
            }
        }
        return out.toByteArray();
    }
}
