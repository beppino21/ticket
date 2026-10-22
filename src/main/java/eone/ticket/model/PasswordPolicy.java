package eone.ticket.model;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * Regole di composizione password, parametrizzate per cliente (Kunnr) in
 * ticket_cliente_config. Gli utenti senza Kunnr (AMS/DISPATCHER/ADMIN) o
 * con un Kunnr non configurato usano {@link #defaultPolicy()}, che
 * corrisponde al comportamento storico (solo lunghezza minima 8).
 *
 * Definizioni usate nel conteggio:
 *  - carattere numerico: Character.isDigit
 *  - carattere speciale: tutto ciò che non è lettera, cifra o spazio
 *  - maiuscole/minuscole: almeno una lettera maiuscola E una minuscola
 */
public class PasswordPolicy implements Serializable {

    private static final long serialVersionUID = 1L;

    public static final int DEFAULT_LUNGHEZZA_MINIMA = 8;
    public static final int DEFAULT_MIN_SPECIALI     = 0;
    public static final int DEFAULT_MIN_NUMERICI     = 0;
    public static final boolean DEFAULT_MAIUSC_MINUSC = false;

    /** Limiti ammessi in configurazione (allineati ai CHECK su DB). */
    public static final int LIMITE_LUNGHEZZA_MIN = 6;
    public static final int LIMITE_LUNGHEZZA_MAX = 64;   // BCrypt considera al massimo 72 byte
    public static final int LIMITE_CONTEGGI_MAX  = 20;

    private int     lunghezzaMinima      = DEFAULT_LUNGHEZZA_MINIMA;
    private int     minCaratteriSpeciali = DEFAULT_MIN_SPECIALI;
    private int     minCaratteriNumerici = DEFAULT_MIN_NUMERICI;
    private boolean obbligoMaiuscMinusc  = DEFAULT_MAIUSC_MINUSC;

    public PasswordPolicy() { }

    public PasswordPolicy(int lunghezzaMinima, int minSpeciali, int minNumerici, boolean maiuscMinusc) {
        this.lunghezzaMinima      = lunghezzaMinima;
        this.minCaratteriSpeciali = minSpeciali;
        this.minCaratteriNumerici = minNumerici;
        this.obbligoMaiuscMinusc  = maiuscMinusc;
    }

    public static PasswordPolicy defaultPolicy() { return new PasswordPolicy(); }

    /**
     * Verifica la password contro la policy.
     * @return null se valida, altrimenti un messaggio che elenca TUTTI i
     *         requisiti non soddisfatti (così l'utente corregge in un colpo solo).
     */
    public String valida(String password) {
        String pwd = password != null ? password : "";
        int speciali = 0, numerici = 0;
        boolean maiusc = false, minusc = false;
        for (int i = 0; i < pwd.length(); i++) {
            char c = pwd.charAt(i);
            if (Character.isDigit(c))           numerici++;
            else if (Character.isLetter(c)) {
                if (Character.isUpperCase(c))   maiusc = true;
                if (Character.isLowerCase(c))   minusc = true;
            }
            else if (!Character.isWhitespace(c)) speciali++;
        }

        List<String> mancanti = new ArrayList<>();
        if (pwd.length() < lunghezzaMinima)
            mancanti.add("almeno " + lunghezzaMinima + " caratteri");
        if (numerici < minCaratteriNumerici)
            mancanti.add("almeno " + minCaratteriNumerici + " caratter" + (minCaratteriNumerici == 1 ? "e numerico" : "i numerici"));
        if (speciali < minCaratteriSpeciali)
            mancanti.add("almeno " + minCaratteriSpeciali + " caratter" + (minCaratteriSpeciali == 1 ? "e speciale" : "i speciali"));
        if (obbligoMaiuscMinusc && !(maiusc && minusc))
            mancanti.add("lettere maiuscole e minuscole");

        if (mancanti.isEmpty()) return null;
        return "La nuova password deve contenere: " + String.join(", ", mancanti) + ".";
    }

    /** Descrizione sintetica per hint a video, es. "minimo 10 caratteri, 1 numero, 1 speciale, maiusc./minusc." */
    public String getDescrizione() {
        StringBuilder sb = new StringBuilder("minimo ").append(lunghezzaMinima).append(" caratteri");
        if (minCaratteriNumerici > 0) sb.append(", ").append(minCaratteriNumerici).append(minCaratteriNumerici == 1 ? " numero" : " numeri");
        if (minCaratteriSpeciali > 0) sb.append(", ").append(minCaratteriSpeciali).append(minCaratteriSpeciali == 1 ? " carattere speciale" : " caratteri speciali");
        if (obbligoMaiuscMinusc)      sb.append(", maiuscole e minuscole");
        return sb.toString();
    }

    /** Descrizione compatta per la colonna della griglia clienti, es. "8 / 1 / 2 / Aa". */
    public String getDescrizioneBreve() {
        return lunghezzaMinima + " / " + minCaratteriSpeciali + " / " + minCaratteriNumerici
             + (obbligoMaiuscMinusc ? " / Aa" : "");
    }

    public int getLunghezzaMinima()               { return lunghezzaMinima; }
    public void setLunghezzaMinima(int v)         { this.lunghezzaMinima = v; }

    public int getMinCaratteriSpeciali()          { return minCaratteriSpeciali; }
    public void setMinCaratteriSpeciali(int v)    { this.minCaratteriSpeciali = v; }

    public int getMinCaratteriNumerici()          { return minCaratteriNumerici; }
    public void setMinCaratteriNumerici(int v)    { this.minCaratteriNumerici = v; }

    public boolean isObbligoMaiuscMinusc()        { return obbligoMaiuscMinusc; }
    public void setObbligoMaiuscMinusc(boolean v) { this.obbligoMaiuscMinusc = v; }

    @Override
    public String toString() { return "PasswordPolicy[" + getDescrizione() + "]"; }
}
