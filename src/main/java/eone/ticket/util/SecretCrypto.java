package eone.ticket.util;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import eone.ticket.config.AppConfig;

/**
 * Cifratura AES-256-GCM dei segreti MFA prima del salvataggio a DB, così
 * un dump del database da solo non basta a generare codici validi.
 *
 * Chiave: MFA_ENC_KEY in config.properties, 32 byte in Base64
 * (generabile con: openssl rand -base64 32). Va conservata FUORI dal DB
 * e senza di essa i segreti salvati non sono più leggibili (gli utenti
 * dovrebbero riattivare l'MFA).
 *
 * Formato salvato: "E1:" + Base64(iv[12] || ciphertext+tag).
 * Se la chiave non è configurata il segreto viene salvato in chiaro con
 * prefisso "P:" e viene emesso un avviso nel log — funziona, ma non è la
 * configurazione da usare in produzione.
 */
public final class SecretCrypto {

    private static final String PREFISSO_CIFRATO = "E1:";
    private static final String PREFISSO_CHIARO  = "P:";
    private static final SecureRandom RNG = new SecureRandom();

    private SecretCrypto() { }

    public static String cifra(String chiaro) {
        byte[] key = chiave();
        if (key == null) {
            System.err.println("[SecretCrypto] ⚠ MFA_ENC_KEY non configurata: segreto MFA salvato NON cifrato");
            return PREFISSO_CHIARO + chiaro;
        }
        try {
            byte[] iv = new byte[12];
            RNG.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
            byte[] ct = c.doFinal(chiaro.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return PREFISSO_CIFRATO + Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("Cifratura segreto MFA fallita", e);
        }
    }

    public static String decifra(String salvato) {
        if (salvato == null) return null;
        if (salvato.startsWith(PREFISSO_CHIARO)) return salvato.substring(PREFISSO_CHIARO.length());
        if (!salvato.startsWith(PREFISSO_CIFRATO)) throw new IllegalStateException("Formato segreto MFA sconosciuto");
        byte[] key = chiave();
        if (key == null) throw new IllegalStateException("MFA_ENC_KEY non configurata: impossibile leggere il segreto MFA");
        try {
            byte[] in = Base64.getDecoder().decode(salvato.substring(PREFISSO_CIFRATO.length()));
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, in, 0, 12));
            byte[] pt = c.doFinal(in, 12, in.length - 12);
            return new String(pt, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Decifratura segreto MFA fallita (chiave MFA_ENC_KEY cambiata?)", e);
        }
    }

    private static byte[] chiave() {
        String b64 = AppConfig.get("MFA_ENC_KEY", null);
        if (b64 == null) return null;
        byte[] k = Base64.getDecoder().decode(b64.trim());
        if (k.length != 32) throw new IllegalStateException("MFA_ENC_KEY deve essere di 32 byte (Base64 di 32 byte)");
        return k;
    }
}
