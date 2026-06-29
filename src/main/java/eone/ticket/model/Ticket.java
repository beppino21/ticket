package eone.ticket.model;

import org.json.JSONObject;

/**
 * Modello per un Ticket SAP restituito dal servizio OData.
 *
 * I campi "enriched" (prefisso enc_) sono transitori — non vengono
 * da SAP ma da PostgreSQL tramite EnrichmentService.enrichTickets().
 */
public class Ticket {

    // =========================
    // CAMPI SAP (originali)
    // =========================

    private String tickt;    // Numero ticket
    private String title;    // Titolo
    private String rstat;    // Stato
    private String rprio;    // Priorità
    private String erdat;    // Data creazione
    private String kunnr;    // Cliente
    private String reqid;    // Richiedente
    private String categ;    // Categoria
    private String prdct;    // Prodotto
    private String modul;    // Modulo
    private String amusr;    // Utente assegnato
    private String refer;    // Riferimento
    private String fathr;    // Padre
    private String comch;    // Canale comunicazione
    private String bukrs;    // Società

    // =========================
    // CAMPI ARRICCHIMENTO (da PostgreSQL — EnrichmentService)
    // =========================

    private int    encNumCommenti   = 0;     // Numero totale commenti
    private int    encNumAllegati   = 0;     // Numero totale allegati (su tutti i commenti)
    private String encUltimoStato   = "";    // Stato dell'ultimo commento
    private String encUltimaData    = "";    // Data/ora dell'ultimo commento (formattata)
    private String encUltimoTesto   = "";    // Testo (troncato) dell'ultimo commento

    // =========================
    // COSTRUTTORI
    // =========================

    public Ticket() {
    }

    public Ticket(JSONObject jsonData) {
        try {
            this.tickt = jsonData.optString("Tickt", "");
            this.title = jsonData.optString("Title", "");
            this.rstat = jsonData.optString("Rstat", "");
            this.rprio = jsonData.optString("Rprio", "");
            this.erdat = jsonData.optString("Erdat", "");
            this.kunnr = jsonData.optString("Kunnr", "");
            this.reqid = jsonData.optString("Reqid", "");
            this.categ = jsonData.optString("Categ", "");
            this.prdct = jsonData.optString("Prdct", "");
            this.modul = jsonData.optString("Modul", "");
            this.amusr = jsonData.optString("Amusr", "");
            this.refer = jsonData.optString("Refer", "");
            this.fathr = jsonData.optString("Fathr", "");
            this.comch = jsonData.optString("Comch", "");
            this.bukrs = jsonData.optString("Bukrs", "");
        } catch (Exception e) {
            System.err.println("[Ticket] Errore nel parsing JSON: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // =========================
    // GETTERS / SETTERS SAP
    // =========================

    public String getTickt()             { return tickt; }
    public void setTickt(String tickt)   { this.tickt = tickt; }

    public String getTitle()             { return title; }
    public void setTitle(String title)   { this.title = title; }

    public String getRstat()             { return rstat; }
    public void setRstat(String rstat)   { this.rstat = rstat; }

    public String getRprio()             { return rprio; }
    public void setRprio(String rprio)   { this.rprio = rprio; }

    public String getErdat()             { return erdat; }
    public void setErdat(String erdat)   { this.erdat = erdat; }

    public String getKunnr()             { return kunnr; }
    public void setKunnr(String kunnr)   { this.kunnr = kunnr; }

    public String getReqid()             { return reqid; }
    public void setReqid(String reqid)   { this.reqid = reqid; }

    public String getCateg()             { return categ; }
    public void setCateg(String categ)   { this.categ = categ; }

    public String getPrdct()             { return prdct; }
    public void setPrdct(String prdct)   { this.prdct = prdct; }

    public String getModul()             { return modul; }
    public void setModul(String modul)   { this.modul = modul; }

    public String getAmusr()             { return amusr; }
    public void setAmusr(String amusr)   { this.amusr = amusr; }

    public String getRefer()             { return refer; }
    public void setRefer(String refer)   { this.refer = refer; }

    public String getFathr()             { return fathr; }
    public void setFathr(String fathr)   { this.fathr = fathr; }

    public String getComch()             { return comch; }
    public void setComch(String comch)   { this.comch = comch; }

    public String getBukrs()             { return bukrs; }
    public void setBukrs(String bukrs)   { this.bukrs = bukrs; }

    // =========================
    // GETTERS / SETTERS ARRICCHIMENTO
    // =========================

    public int getEncNumCommenti()                   { return encNumCommenti; }
    public void setEncNumCommenti(int n)             { this.encNumCommenti = n; }

    public int getEncNumAllegati()                   { return encNumAllegati; }
    public void setEncNumAllegati(int n)             { this.encNumAllegati = n; }

    public String getEncUltimoStato()                { return encUltimoStato != null ? encUltimoStato : ""; }
    public void setEncUltimoStato(String s)          { this.encUltimoStato = s; }

    public String getEncUltimaData()                 { return encUltimaData != null ? encUltimaData : ""; }
    public void setEncUltimaData(String s)           { this.encUltimaData = s; }

    public String getEncUltimoTesto()                { return encUltimoTesto != null ? encUltimoTesto : ""; }
    public void setEncUltimoTesto(String s)          { this.encUltimoTesto = s; }

    /** True se esiste almeno un commento da PostgreSQL */
    public boolean getEncHasCommenti()               { return encNumCommenti > 0; }

    /** Etichetta commenti per grid (es. "3 comm. / 5 all.") */
    public String getEncSommario() {
        if (encNumCommenti == 0) return "";
        return encNumCommenti + " comm. / " + encNumAllegati + " all.";
    }

    // =========================
    // UTILITY
    // =========================

    @Override
    public String toString() {
        return "Ticket{tickt='" + tickt + "', title='" + title + "'"
             + ", rstat='" + rstat + "', rprio='" + rprio + "'"
             + ", kunnr='" + kunnr + "'}";
    }
}
