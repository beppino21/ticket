package eone.ticket.service;

import java.io.DataOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

import eone.ticket.config.AppConfig;

/**
 * Controlli di sicurezza sugli allegati caricati dagli utenti, in
 * aggiunta ai limiti dimensionali di {@link AttachmentLimits}:
 *
 *  1. nome file ripulito (niente percorsi, caratteri di controllo,
 *     lunghezza eccessiva);
 *  2. estensione nell'elenco ammesso (ALLEGATI_ESTENSIONI in
 *     config.properties; default sotto). Si guarda l'ULTIMA estensione,
 *     quindi "fattura.pdf.exe" è rifiutato;
 *  3. contenuto coerente con l'estensione (firma dei primi byte): un
 *     eseguibile rinominato in .pdf viene rifiutato; un eseguibile
 *     Windows/Linux è rifiutato sempre, qualunque nome abbia;
 *  4. antivirus ClamAV (opzionale): se CLAMAV_HOST è configurato il file
 *     viene inviato al demone clamd; se clamd non risponde il file è
 *     rifiutato (ALLEGATI_AV_FAIL_CLOSED=true, default) o accettato con
 *     avviso nel log (false).
 */
public final class AttachmentValidator {

    /** Elenco di default: documenti, immagini, archivi zip, mail. Esclusi formati con macro (docm, xlsm) ed eseguibili. */
    public static final String ESTENSIONI_DEFAULT =
        "pdf,png,jpg,jpeg,gif,bmp,tif,tiff,webp,docx,xlsx,pptx,doc,xls,ppt,odt,ods,csv,txt,log,xml,json,zip,7z,msg,eml";

    private static final int MAX_NOME = 200;

    private AttachmentValidator() { }

    /** Esito della validazione: errore == null → file accettato (con nome ripulito). */
    public static class Esito {
        public final String errore;
        public final String nomePulito;
        Esito(String errore, String nomePulito) { this.errore = errore; this.nomePulito = nomePulito; }
        public boolean isOk() { return errore == null; }
    }

    public static Esito valida(String nomeOriginale, byte[] dati) {
        String nome = pulisciNome(nomeOriginale);
        if (nome.isEmpty()) return new Esito("nome file non valido", nome);

        String ext = estensione(nome);
        if (ext.isEmpty() || !estensioniAmmesse().contains(ext)) {
            return new Esito("tipo di file non ammesso (." + (ext.isEmpty() ? "?" : ext) + "). Ammessi: "
                             + String.join(", ", estensioniAmmesse()), nome);
        }
        if (dati == null || dati.length == 0) return new Esito("file vuoto", nome);

        // Per i formati testuali il controllo "nessun byte NUL" basta a escludere i binari
        // (e un CSV che inizia per "MZ" non va scambiato per un eseguibile).
        if (!isTestuale(ext) && isEseguibile(dati)) return new Esito("il file contiene un programma eseguibile", nome);

        if (!contenutoCoerente(ext, dati)) {
            return new Esito("il contenuto del file non corrisponde a un ." + ext, nome);
        }

        String av = scansioneAntivirus(dati);
        if (av != null) return new Esito(av, nome);

        return new Esito(null, nome);
    }

    // ------------------------------------------------------------------

    /** Toglie eventuale percorso (C:\\..\\ o /...), caratteri di controllo e riservati, e limita la lunghezza. */
    public static String pulisciNome(String n) {
        if (n == null) return "";
        String s = n;
        int slash = Math.max(s.lastIndexOf('/'), s.lastIndexOf('\\'));
        if (slash >= 0) s = s.substring(slash + 1);
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (c < 0x20 || c == 0x7F || "<>:\"|?*".indexOf(c) >= 0) sb.append('_');
            else sb.append(c);
        }
        s = sb.toString().trim();
        while (s.startsWith(".")) s = s.substring(1);   // niente file "nascosti" / ".."
        if (s.length() > MAX_NOME) {
            String ext = estensione(s);
            int keep = MAX_NOME - (ext.isEmpty() ? 0 : ext.length() + 1);
            s = s.substring(0, keep) + (ext.isEmpty() ? "" : "." + ext);
        }
        return s;
    }

    public static String estensione(String nome) {
        int dot = nome.lastIndexOf('.');
        if (dot < 0 || dot == nome.length() - 1) return "";
        return nome.substring(dot + 1).toLowerCase(Locale.ROOT).trim();
    }

    public static Set<String> estensioniAmmesse() {
        Set<String> set = new LinkedHashSet<>();
        for (String e : AppConfig.get("ALLEGATI_ESTENSIONI", ESTENSIONI_DEFAULT).split(",")) {
            String x = e.trim().toLowerCase(Locale.ROOT);
            if (x.startsWith(".")) x = x.substring(1);
            if (!x.isEmpty()) set.add(x);
        }
        return set;
    }

    private static boolean inizia(byte[] d, int... firma) {
        if (d.length < firma.length) return false;
        for (int i = 0; i < firma.length; i++) if ((d[i] & 0xFF) != firma[i]) return false;
        return true;
    }

    /** Eseguibili Windows (MZ), Linux (ELF), macOS (Mach-O), script con shebang. */
    static boolean isEseguibile(byte[] d) {
        return inizia(d, 'M', 'Z')
            || inizia(d, 0x7F, 'E', 'L', 'F')
            || inizia(d, 0xCF, 0xFA, 0xED, 0xFE) || inizia(d, 0xCE, 0xFA, 0xED, 0xFE)
            || inizia(d, 0xFE, 0xED, 0xFA, 0xCF) || inizia(d, 0xCA, 0xFE, 0xBA, 0xBE)
            || inizia(d, '#', '!');
    }

    static boolean contenutoCoerente(String ext, byte[] d) {
        switch (ext) {
            case "pdf":  return indexOf(d, "%PDF-".getBytes(StandardCharsets.US_ASCII), 1024) >= 0;
            case "png":  return inizia(d, 0x89, 'P', 'N', 'G');
            case "jpg": case "jpeg": return inizia(d, 0xFF, 0xD8, 0xFF);
            case "gif":  return inizia(d, 'G', 'I', 'F', '8');
            case "bmp":  return inizia(d, 'B', 'M');
            case "tif": case "tiff": return inizia(d, 'I', 'I', 0x2A, 0x00) || inizia(d, 'M', 'M', 0x00, 0x2A);
            case "webp": return inizia(d, 'R', 'I', 'F', 'F') && d.length > 12 && d[8] == 'W' && d[9] == 'E' && d[10] == 'B' && d[11] == 'P';
            // Office moderni e OpenDocument sono archivi ZIP
            case "docx": case "xlsx": case "pptx": case "odt": case "ods": case "zip":
                return inizia(d, 'P', 'K', 0x03, 0x04) || inizia(d, 'P', 'K', 0x05, 0x06);
            case "7z":   return inizia(d, '7', 'z', 0xBC, 0xAF, 0x27, 0x1C);
            // Office 97-2003 e Outlook .msg: contenitore OLE2
            case "doc": case "xls": case "ppt": case "msg":
                return inizia(d, 0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1);
            // Formati testuali: nessun byte NUL nei primi 8 KB (= non binario)
            case "txt": case "log": case "csv": case "xml": case "json": case "eml":
                return !contieneNul(d, 8192);
            default:
                // Estensione aggiunta in config senza firma nota: accettata (resta il controllo eseguibili + AV)
                return true;
        }
    }

    static boolean isTestuale(String ext) {
        switch (ext) {
            case "txt": case "log": case "csv": case "xml": case "json": case "eml": return true;
            default: return false;
        }
    }

    private static boolean contieneNul(byte[] d, int max) {
        int n = Math.min(d.length, max);
        for (int i = 0; i < n; i++) if (d[i] == 0) return true;
        return false;
    }

    private static int indexOf(byte[] d, byte[] pat, int max) {
        int n = Math.min(d.length, max) - pat.length;
        outer:
        for (int i = 0; i <= n; i++) {
            for (int j = 0; j < pat.length; j++) if (d[i + j] != pat[j]) continue outer;
            return i;
        }
        return -1;
    }

    // ------------------------------------------------------------------
    // ClamAV (protocollo clamd INSTREAM)
    // ------------------------------------------------------------------

    /** null = pulito o antivirus non configurato; altrimenti messaggio d'errore per l'utente. */
    static String scansioneAntivirus(byte[] dati) {
        String host = AppConfig.get("CLAMAV_HOST", null);
        if (host == null) return null;
        int port    = parse(AppConfig.get("CLAMAV_PORT", "3310"), 3310);
        int timeout = parse(AppConfig.get("CLAMAV_TIMEOUT_MS", "15000"), 15000);
        boolean failClosed = AppConfig.getBoolean("ALLEGATI_AV_FAIL_CLOSED", true);
        try (Socket sock = new Socket()) {
            sock.connect(new InetSocketAddress(host, port), timeout);
            sock.setSoTimeout(timeout);
            OutputStream raw = sock.getOutputStream();
            DataOutputStream out = new DataOutputStream(raw);
            out.write("zINSTREAM\0".getBytes(StandardCharsets.US_ASCII));
            int chunk = 8192;
            for (int off = 0; off < dati.length; off += chunk) {
                int len = Math.min(chunk, dati.length - off);
                out.writeInt(len);
                out.write(dati, off, len);
            }
            out.writeInt(0);
            out.flush();
            InputStream in = sock.getInputStream();
            byte[] buf = new byte[512];
            StringBuilder resp = new StringBuilder();
            int r;
            while ((r = in.read(buf)) > 0) {
                resp.append(new String(buf, 0, r, StandardCharsets.US_ASCII));
                if (resp.indexOf("\0") >= 0) break;
            }
            String esito = resp.toString().replace("\0", "").trim();
            if (esito.endsWith("OK")) return null;
            if (esito.endsWith("FOUND")) {
                System.err.println("[AttachmentValidator] ⚠ ClamAV: " + esito);
                return "il file è stato segnalato dall'antivirus";
            }
            System.err.println("[AttachmentValidator] Risposta ClamAV inattesa: " + esito);
            return failClosed ? "verifica antivirus non riuscita, riprovare più tardi" : null;
        } catch (Exception e) {
            System.err.println("[AttachmentValidator] ClamAV non raggiungibile (" + host + ":" + port + "): " + e.getMessage());
            return failClosed ? "verifica antivirus non disponibile, riprovare più tardi" : null;
        }
    }

    private static int parse(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return def; }
    }
}
