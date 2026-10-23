package eone.ticket.util;

import jakarta.servlet.http.HttpServletRequest;

import org.eclnt.jsfserver.util.HttpSessionAccess;

/**
 * Informazioni sul client della richiesta corrente (IP), per audit log.
 *
 * Registra SEMPRE l'indirizzo del socket (getRemoteAddr) e, se presente,
 * anche l'header X-Forwarded-For tra parentesi: l'header è impostato dal
 * reverse proxy ma può anche essere falsificato da un client diretto,
 * quindi non lo si sostituisce all'indirizzo reale — lo si affianca.
 *
 * Fuori da una richiesta CaptainCasa (es. scheduler) restituisce null.
 */
public final class ClientInfo {

    private ClientInfo() { }

    public static String getClientIp() {
        try {
            HttpServletRequest req = HttpSessionAccess.getCurrentRequest();
            if (req == null) return null;
            String remote = req.getRemoteAddr();
            String xff = req.getHeader("X-Forwarded-For");
            String ip = remote != null ? remote : "?";
            if (xff != null && !xff.trim().isEmpty()) {
                String primo = xff.split(",")[0].trim();
                if (!primo.equals(remote)) ip += " (xff " + primo + ")";
            }
            return ip.length() > 100 ? ip.substring(0, 100) : ip;
        } catch (Throwable t) {
            return null;
        }
    }
}
