package eone.ticket.model;

import java.io.Serializable;

/** Configurazione di abilitazione di un cliente (Kunnr) alla nuova gestione ticket. */
public class ClienteConfig implements Serializable {

    private static final long serialVersionUID = 1L;

    private String  kunnr;
    private String  nomeCliente;
    private boolean abilitato;
    private String  prefissoReferente;

    public String getKunnr()                { return kunnr; }
    public void setKunnr(String v)           { this.kunnr = v; }

    public String getNomeCliente()           { return nomeCliente; }
    public void setNomeCliente(String v)     { this.nomeCliente = v; }

    public boolean isAbilitato()             { return abilitato; }
    public void setAbilitato(boolean v)      { this.abilitato = v; }

    public String getPrefissoReferente()     { return prefissoReferente; }
    public void setPrefissoReferente(String v) { this.prefissoReferente = v; }
}