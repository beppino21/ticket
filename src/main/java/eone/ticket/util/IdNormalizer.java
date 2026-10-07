package eone.ticket.util;

import java.util.Collection;

/**
 * Normalizzazione dei codici identificativi (Reqid, Kunnr) per CONFRONTARLI
 * tra sistemi che li rappresentano in modo diverso.
 *
 * Caso reale: in SAP/Newton il Reqid è un campo di 4 caratteri con zeri a
 * sinistra ("0001"), ma i ticket letti via OData lo restituiscono senza zeri
 * ("1"), mentre ticket_user lo contiene nella forma usata per il filtro verso
 * SAP ("0001" per alcuni clienti, "2" per altri). Stessa persona, stringhe
 * diverse: i confronti diretti falliscono.
 *
 * Regola unica: se il valore è fatto di sole cifre si tolgono gli zeri iniziali
 * ("0001" -> "1", "0000" -> "0"); altrimenti trim + maiuscolo.
 *
 * IMPORTANTE: serve SOLO per confrontare. I valori memorizzati (ticket_user,
 * ticket_draft, ticket_referente) e quelli inviati nel $filter verso SAP NON
 * vanno normalizzati: restano nella forma originale.
 */
public final class IdNormalizer {

    private IdNormalizer() {}

    /** Forma canonica di un codice (Reqid o Kunnr) — mai null. */
    public static String normalize(String s) {
        if (s == null) return "";
        String t = s.trim();
        if (t.isEmpty()) return "";
        if (t.matches("\\d+")) {
            String stripped = t.replaceFirst("^0+", "");
            return stripped.isEmpty() ? "0" : stripped;
        }
        return t.toUpperCase();
    }

    public static String reqid(String s) { return normalize(s); }

    /** True se i due codici coincidono a meno di zeri iniziali/maiuscole/spazi. Due vuoti NON coincidono. */
    public static boolean sameReqid(String a, String b) {
        String na = normalize(a);
        return !na.isEmpty() && na.equals(normalize(b));
    }

    /** True se nella collezione c'è un codice equivalente a {@code reqid}. */
    public static boolean containsReqid(Collection<String> set, String reqid) {
        if (set == null || set.isEmpty()) return false;
        String n = normalize(reqid);
        if (n.isEmpty()) return false;
        for (String s : set) {
            if (n.equals(normalize(s))) return true;
        }
        return false;
    }

    /**
     * Espressione SQL (PostgreSQL) equivalente a {@link #normalize(String)}
     * applicata a una colonna — da confrontare con un parametro già normalizzato in Java.
     */
    public static String sqlNormalize(String column) {
        return "(CASE WHEN TRIM(" + column + ") ~ '^[0-9]+$' " +
               "THEN COALESCE(NULLIF(LTRIM(TRIM(" + column + "), '0'), ''), '0') " +
               "ELSE UPPER(TRIM(" + column + ")) END)";
    }
}
