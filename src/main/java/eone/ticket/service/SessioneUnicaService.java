package eone.ticket.service;

import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Una sola sessione attiva per utente ("l'ultimo accesso vince").
 *
 * Al logon riuscito l'utente riceve un identificativo di sessione nuovo e
 * quello precedente, se c'era, smette di essere valido: la sessione vecchia
 * se ne accorge alla prima interazione (OutestUI.getContentUI e
 * SessionGuard) e torna alla pagina di logon con un messaggio.
 *
 * Perché non "si rifiuta il secondo logon": chi chiude il browser senza
 * logout lascerebbe una sessione viva fino al timeout e resterebbe chiuso
 * fuori. Così invece il nuovo accesso è sempre possibile, e se qualcuno usa
 * le credenziali di un altro il legittimo titolare viene disconnesso e se
 * ne accorge (evento SESSIONE_SOSTITUITA nel registro attività).
 *
 * Il registro è in memoria: vale per una singola installazione Tomcat.
 * Con l'eventuale separazione tra installazione esterna (clienti) e interna
 * (e-One) basta, perché ogni utente accede da una sola delle due.
 * Lo username è confrontato senza distinzione di maiuscole/minuscole,
 * come al logon.
 */
public final class SessioneUnicaService {

    /** username (minuscolo) -> identificativo della sessione attiva */
    private static final ConcurrentHashMap<String, String> ATTIVE = new ConcurrentHashMap<>();

    private SessioneUnicaService() { }

    private static String chiave(String idUser) {
        return idUser == null ? "" : idUser.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Registra la nuova sessione dell'utente e restituisce il suo
     * identificativo. Una sessione precedente dello stesso utente diventa
     * non valida.
     */
    public static String registra(String idUser, String kunnr, String ruolo) {
        String token    = UUID.randomUUID().toString();
        String precedente = ATTIVE.put(chiave(idUser), token);
        if (precedente != null) {
            AuditService.log(idUser, kunnr, ruolo, AuditService.SESSIONE_SOSTITUITA, AuditService.ESITO_OK, idUser,
                             "nuovo accesso: la sessione precedente dello stesso utente viene chiusa");
            System.out.println("[SessioneUnica] Nuovo accesso di " + idUser + ": sessione precedente sostituita");
        }
        return token;
    }

    /** True se il token è ancora quello della sessione attiva dell'utente. */
    public static boolean isValida(String idUser, String token) {
        return token != null && token.equals(ATTIVE.get(chiave(idUser)));
    }

    /**
     * Logout, uscita o chiusura della sessione: toglie la voce SOLO se è
     * ancora la propria (non cancella la sessione più recente dello stesso utente).
     */
    public static void rilascia(String idUser, String token) {
        if (idUser != null && token != null) ATTIVE.remove(chiave(idUser), token);
    }
}
