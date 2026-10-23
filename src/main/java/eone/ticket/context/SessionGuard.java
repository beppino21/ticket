package eone.ticket.context;

import java.util.Arrays;
import java.util.function.Predicate;

import eone.ticket.model.RequesterInfo;
import eone.ticket.service.AuditService;

/**
 * Secondo livello di difesa sulle pagine: ogni page bean protetto chiama
 * SessionGuard nel proprio COSTRUTTORE. Se la pagina viene aperta
 * direttamente da URL (es. .../AuditLog.risc) senza un utente autenticato
 * con il ruolo richiesto, il bean non viene creato, la pagina non parte e il
 * tentativo viene registrato nel registro attività (ACCESSO_NEGATO).
 *
 * Il primo livello è la configurazione CaptainCasa in system.xml
 * (<riscstarter> con <allowstart page="/Outest.*"/>): questo controllo resta
 * efficace anche se quella configurazione venisse persa o modificata.
 *
 * Non va usato in LogonUI, OutestUI e ChangePasswordUI, che funzionano
 * prima del logon.
 */
public final class SessionGuard {

    private SessionGuard() { }

    /** Qualunque utente autenticato. */
    public static void richiediUtente(String pagina) {
        richiedi(pagina, "utente autenticato", info -> true);
    }

    /** Utente autenticato con uno dei ruoli indicati. */
    public static void richiediRuolo(String pagina, String... ruoli) {
        richiedi(pagina, "ruolo " + String.join("/", ruoli),
                 info -> info.getRuolo() != null
                      && Arrays.stream(ruoli).anyMatch(r -> r.equalsIgnoreCase(info.getRuolo().trim())));
    }

    /** Utente autenticato che soddisfa una condizione specifica. */
    public static void richiedi(String pagina, String requisito, Predicate<RequesterInfo> condizione) {
        RequesterInfo info = null;
        try {
            info = ViewSessionContext.instance().getRequesterInfo();
        } catch (Throwable t) {
            // nessuna sessione di dialogo: trattato come non autenticato
        }
        boolean ok = info != null && condizione.test(info);
        // Traccia sempre il controllo (poche righe: solo alla creazione delle pagine)
        System.out.println("[SessionGuard] controllo " + pagina + ": utente="
            + (info != null ? info.getId_user() + " ruolo=" + info.getRuolo() : "nessuno")
            + " -> " + (ok ? "OK" : "NEGATO"));
        if (ok) return;

        String motivo = info == null ? "nessun utente autenticato in sessione"
                                     : "utente " + info.getId_user() + " (ruolo " + info.getRuolo() + ") senza " + requisito;
        AuditService.log(info != null ? info.getId_user() : null,
                         info != null ? info.getKunnr() : null,
                         info != null ? info.getRuolo() : null,
                         AuditService.ACCESSO_NEGATO, AuditService.ESITO_FAIL, pagina, motivo);
        System.err.println("[SessionGuard] ⛔ Accesso negato a " + pagina + ": " + motivo);
        throw new SecurityException("Accesso non autorizzato");
    }
}
