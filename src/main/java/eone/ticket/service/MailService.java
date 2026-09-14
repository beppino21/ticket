package eone.ticket.service;

import java.util.Properties;

import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.util.ByteArrayDataSource;
import jakarta.activation.DataHandler;

import eone.ticket.config.AppConfig;
import eone.ticket.model.TicketAttachment;

import java.util.List;

/**
 * Servizio invio email di notifica per commenti sui ticket.
 *
 * STATO ATTUALE: in attesa di credenziali SMTP. Finché MAIL_HOST non è
 * configurato, il metodo sendNotificaCommento() LOGGA il contenuto invece
 * di inviarlo davvero — così l'integrazione con saveComment() è già
 * completa e testabile, e basterà valorizzare le env vars per attivarla.
 *
 * Configurazione attesa (variabili d'ambiente):
 *   MAIL_HOST       - es. smtp.office365.com
 *   MAIL_PORT       - es. 587
 *   MAIL_USER       - account mittente
 *   MAIL_PASS       - password/app-password
 *   MAIL_FROM       - indirizzo mittente visualizzato (default = MAIL_USER)
 *   MAIL_DRY_RUN    - "true" (default) per loggare senza inviare, "false" per inviare davvero
 */
public class MailService {

    private static boolean isDryRun() {
        String host = AppConfig.get("MAIL_HOST", null);
        if (host == null || host.trim().isEmpty()) return true; // nessuna config -> sempre dry-run
        return AppConfig.getBoolean("MAIL_DRY_RUN", true);
    }

    /**
     * Invia (o logga, in dry-run) la notifica di un nuovo commento.
     *
     * @param toEmail     destinatario
     * @param tickt       numero ticket
     * @param statoLabel  etichetta leggibile dello stato impostato col commento
     * @param autoreId    chi ha scritto il commento
     * @param testoCompleto testo integrale del commento (per il body)
     * @param allegati    eventuali allegati pending da includere nella mail
     */
    public void sendNotificaCommento(String toEmail, String tickt, String statoLabel,
                                      String autoreId, String testoCompleto,
                                      List<TicketAttachment> allegati) {
        sendNotificaCommento(toEmail, tickt, statoLabel, autoreId, testoCompleto, allegati, null);
    }

    /**
     * Come sopra, con una nota facoltativa inserita nel corpo (es. "Ricevi
     * questa comunicazione in quanto Referente del ticket.") — usata per
     * distinguere il motivo per cui un destinatario riceve la notifica
     * quando non è l'AMS assegnato principale.
     */
    public void sendNotificaCommento(String toEmail, String tickt, String statoLabel,
                                      String autoreId, String testoCompleto,
                                      List<TicketAttachment> allegati, String notaAggiuntiva) {

        if (toEmail == null || toEmail.trim().isEmpty()) {
            System.out.println("[MailService] Destinatario vuoto, notifica saltata (ticket " + tickt + ")");
            return;
        }

        String testoBreve = testoCompleto != null && testoCompleto.length() > 100
            ? testoCompleto.substring(0, 97) + "..." : testoCompleto;
        String subject = "Ticket " + tickt + " — " + statoLabel + " — " + nn(testoBreve);
        String body = buildBody(tickt, statoLabel, autoreId, testoCompleto, allegati, notaAggiuntiva);

        if (isDryRun()) {
            System.out.println("========== [MailService] DRY-RUN — email non inviata ==========");
            System.out.println("To:      " + toEmail);
            System.out.println("Subject: " + subject);
            System.out.println("Body:\n" + body);
            if (allegati != null && !allegati.isEmpty()) {
                System.out.println("Allegati: " + allegati.size() + " file");
                for (TicketAttachment a : allegati) {
                    System.out.println("  - " + a.getFilename() + " (" + a.getFileSizeFormatted() + ")");
                }
            }
            System.out.println("==================================================================");
            return;
        }

        try {
            send(toEmail, subject, body, allegati);
            System.out.println("[MailService] Email inviata a " + toEmail + " per ticket " + tickt);
        } catch (Exception e) {
            // Non propaghiamo l'eccezione: un fallimento email non deve bloccare
            // il salvataggio del commento, che è già avvenuto con successo.
            System.err.println("[MailService] Errore invio email a " + toEmail + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    private String buildBody(String tickt, String statoLabel, String autoreId,
                              String testoCompleto, List<TicketAttachment> allegati) {
        return buildBody(tickt, statoLabel, autoreId, testoCompleto, allegati, null);
    }

    private String buildBody(String tickt, String statoLabel, String autoreId,
                              String testoCompleto, List<TicketAttachment> allegati, String notaAggiuntiva) {
        StringBuilder sb = new StringBuilder();
        sb.append("Ticket: ").append(tickt).append("\n");
        sb.append("Stato:  ").append(statoLabel).append("\n");
        sb.append("Autore: ").append(nn(autoreId)).append("\n");
        if (notaAggiuntiva != null && !notaAggiuntiva.trim().isEmpty()) {
            sb.append(notaAggiuntiva.trim()).append("\n");
        }
        sb.append("\n");
        sb.append("Commento:\n");
        sb.append(nn(testoCompleto)).append("\n");
        if (allegati != null && !allegati.isEmpty()) {
            sb.append("\nAllegati: ").append(allegati.size());
        }
        String link = buildTicketLink(tickt);
        if (link != null) {
            sb.append("\n\nApri il ticket: ").append(link);
        }
        return sb.toString();
    }

    /**
     * Costruisce il link diretto al ticket (deep link, letto da OutestUI al
     * primo accesso e usato per aprire subito il ticket dopo il logon).
     * Torna null se APP_BASE_URL non è configurato — l'email viene comunque
     * inviata, semplicemente senza il link.
     */
    private String buildTicketLink(String tickt) {
        String baseUrl = AppConfig.get("APP_BASE_URL", null);
        if (baseUrl == null || baseUrl.trim().isEmpty() || tickt == null || tickt.trim().isEmpty()) {
            return null;
        }
        String base = baseUrl.trim();
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return base + "/Outest.risc?ticket=" + tickt.trim();
    }

    /**
     * Avvisa il sostituto di essere stato designato — con periodo di
     * validità ed elenco dei ticket di cui si farà carico (SAP + eventuali
     * DRAFT), così arriva già informato invece di scoprirlo aprendo l'app.
     */
    /**
     * Sollecito aggregato al backoffice: un'unica mail con tutti i ticket
     * fermi da troppi giorni in stato "Richiesta chiusura" o "Ticket
     * risolto" — non ancora chiusi sul backend SAP.
     */
    public void sendSollecitoChiusura(String toEmail, List<String> righeTicket) {
        if (toEmail == null || toEmail.trim().isEmpty()) {
            System.out.println("[MailService] Sollecito chiusura saltato: destinatario vuoto");
            return;
        }
        if (righeTicket == null || righeTicket.isEmpty()) {
            System.out.println("[MailService] Sollecito chiusura saltato: nessun ticket da segnalare");
            return;
        }

        String subject = "Sollecito chiusura — " + righeTicket.size() +
                          (righeTicket.size() == 1 ? " ticket da chiudere" : " ticket da chiudere");

        StringBuilder sb = new StringBuilder();
        sb.append("I seguenti ticket risultano segnalati come risolti/da chiudere dal cliente,\n");
        sb.append("ma non ancora chiusi sul backend SAP:\n\n");
        for (String riga : righeTicket) sb.append("- ").append(riga).append("\n");
        String body = sb.toString();

        if (isDryRun()) {
            System.out.println("========== [MailService] DRY-RUN — sollecito chiusura non inviato ==========");
            System.out.println("To:      " + toEmail);
            System.out.println("Subject: " + subject);
            System.out.println("Body:\n" + body);
            System.out.println("==================================================================");
            return;
        }

        try {
            send(toEmail, subject, body, null);
            System.out.println("[MailService] Sollecito chiusura inviato a " + toEmail + " (" + righeTicket.size() + " ticket)");
        } catch (Exception e) {
            System.err.println("[MailService] Errore invio sollecito chiusura a " + toEmail + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    public void sendNotificaSostituzione(String toEmail, String nomeSostituito,
                                          java.time.LocalDate dataInizio, java.time.LocalDate dataFine,
                                          List<String> righeTicket) {
        if (toEmail == null || toEmail.trim().isEmpty()) {
            System.out.println("[MailService] Notifica sostituzione saltata: destinatario vuoto");
            return;
        }

        java.time.format.DateTimeFormatter fmt = java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy");
        String subject = "Sei stato designato sostituto di " + nn(nomeSostituito) +
                          " dal " + dataInizio.format(fmt) + " al " + dataFine.format(fmt);

        StringBuilder sb = new StringBuilder();
        sb.append("Sei stato designato come sostituto di ").append(nn(nomeSostituito)).append("\n");
        sb.append("Periodo: dal ").append(dataInizio.format(fmt)).append(" al ").append(dataFine.format(fmt)).append(" (estremi inclusi)\n");
        sb.append("\n");
        if (righeTicket == null || righeTicket.isEmpty()) {
            sb.append("Al momento non risultano ticket a suo carico.\n");
        } else {
            sb.append("Ticket attualmente a suo carico (").append(righeTicket.size()).append("):\n");
            for (String riga : righeTicket) sb.append("- ").append(riga).append("\n");
        }
        String body = sb.toString();

        if (isDryRun()) {
            System.out.println("========== [MailService] DRY-RUN — notifica sostituzione non inviata ==========");
            System.out.println("To:      " + toEmail);
            System.out.println("Subject: " + subject);
            System.out.println("Body:\n" + body);
            System.out.println("==================================================================");
            return;
        }

        try {
            send(toEmail, subject, body, null);
            System.out.println("[MailService] Notifica sostituzione inviata a " + toEmail);
        } catch (Exception e) {
            System.err.println("[MailService] Errore invio notifica sostituzione a " + toEmail + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Notifica il cambio di referente su un ticket — usata sia per la prima
     * attribuzione (altroNome = null, nessun referente precedente) sia per
     * una riassegnazione (altroNome = nome del referente precedente/nuovo).
     * Tono solo informativo, nessuna azione richiesta.
     *
     * @param seiIlNuovo true se il destinatario è il NUOVO referente
     *                   (altroNome = nome del precedente, o null se prima
     *                   attribuzione), false se il destinatario è il
     *                   VECCHIO referente appena rimosso (altroNome = nome
     *                   del nuovo, sempre valorizzato in questo caso).
     */
    public void sendNotificaCambioReferente(String toEmail, String tickt, boolean seiIlNuovo, String altroNome) {
        if (toEmail == null || toEmail.trim().isEmpty()) {
            System.out.println("[MailService] Notifica cambio referente saltata: destinatario vuoto (tickt=" + tickt + ")");
            return;
        }

        String subject;
        String body;
        if (seiIlNuovo) {
            subject = "Sei il referente del ticket " + nn(tickt);
            body = "Ricevi questa mail perché ora sei il referente per il ticket " + nn(tickt) +
                   (altroNome != null && !altroNome.trim().isEmpty()
                       ? ". Il precedente referente era l'utente " + altroNome
                       : "") + "\n";
        } else {
            subject = "Non sei più referente del ticket " + nn(tickt);
            body = "Ricevi questa mail perché ora non sei più il referente per il ticket " + nn(tickt) +
                   ". Il nuovo referente è l'utente " + nn(altroNome) + "\n";
        }
        String link = buildTicketLink(tickt);
        if (link != null) {
            body += "\nApri il ticket: " + link + "\n";
        }

        if (isDryRun()) {
            System.out.println("========== [MailService] DRY-RUN — notifica cambio referente non inviata ==========");
            System.out.println("To:      " + toEmail);
            System.out.println("Subject: " + subject);
            System.out.println("Body:\n" + body);
            System.out.println("==================================================================");
            return;
        }

        try {
            send(toEmail, subject, body, null);
            System.out.println("[MailService] Notifica cambio referente (" + (seiIlNuovo ? "nuovo" : "vecchio") +
                               ") inviata a " + toEmail + " per ticket " + tickt);
        } catch (Exception e) {
            System.err.println("[MailService] Errore invio notifica cambio referente a " + toEmail + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Notifica immediata (non aggregata, non attende i giorni di soglia) al
     * momento in cui il cliente imposta uno stato "conclusivo" — Richiesta
     * chiusura, Richiesta cancellazione, Ticket risolto. Usa lo stesso
     * destinatario del sollecito aggregato giornaliero (SOLLECITO_CHIUSURA_EMAIL),
     * ma è un avviso a sé, inviato subito.
     */
    public void sendNotificaStatoConclusivo(String toEmail, String tickt, String statoLabel, String autoreNome) {
        if (toEmail == null || toEmail.trim().isEmpty()) {
            System.out.println("[MailService] Notifica stato conclusivo saltata: destinatario vuoto (tickt=" + tickt + ")");
            return;
        }

        String subject = "Ticket " + nn(tickt) + " — " + nn(statoLabel);
        String body = "Il ticket " + nn(tickt) + " è stato impostato allo stato \"" + nn(statoLabel) + "\"" +
                      (autoreNome != null && !autoreNome.trim().isEmpty() ? " da " + autoreNome : "") + ".\n";

        if (isDryRun()) {
            System.out.println("========== [MailService] DRY-RUN — notifica stato conclusivo non inviata ==========");
            System.out.println("To:      " + toEmail);
            System.out.println("Subject: " + subject);
            System.out.println("Body:\n" + body);
            System.out.println("==================================================================");
            return;
        }

        try {
            send(toEmail, subject, body, null);
            System.out.println("[MailService] Notifica stato conclusivo (" + statoLabel + ") inviata a " + toEmail + " per ticket " + tickt);
        } catch (Exception e) {
            System.err.println("[MailService] Errore invio notifica stato conclusivo a " + toEmail + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Notifica il richiedente AMS che il DISPATCHER ha rifiutato la sua
     * richiesta di riattribuzione — il ticket resta assegnato a lui.
     */
    public void sendNotificaRiassegnazioneRifiutata(String toEmail, String tickt, String motivoOriginale) {
        if (toEmail == null || toEmail.trim().isEmpty()) {
            System.out.println("[MailService] Notifica rifiuto riattribuzione saltata: destinatario vuoto (tickt=" + tickt + ")");
            return;
        }

        String subject = "Richiesta di riattribuzione rifiutata — Ticket " + nn(tickt);
        String body = "La tua richiesta di riattribuzione per il ticket " + nn(tickt) +
                      " è stata rifiutata dal DISPATCHER: il ticket resta assegnato a te.\n\n" +
                      "Motivo che avevi indicato:\n" + nn(motivoOriginale) + "\n";

        if (isDryRun()) {
            System.out.println("========== [MailService] DRY-RUN — notifica rifiuto riattribuzione non inviata ==========");
            System.out.println("To:      " + toEmail);
            System.out.println("Subject: " + subject);
            System.out.println("Body:\n" + body);
            System.out.println("==================================================================");
            return;
        }

        try {
            send(toEmail, subject, body, null);
            System.out.println("[MailService] Notifica rifiuto riattribuzione inviata a " + toEmail + " per ticket " + tickt);
        } catch (Exception e) {
            System.err.println("[MailService] Errore invio notifica rifiuto riattribuzione a " + toEmail + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Notifica l'eliminazione di un DRAFT prima che venga smistato in SAP —
     * stesso destinatari della creazione (tutti i DISPATCHER attivi + il
     * referente indicato, se presente), tono solo informativo.
     */
    public void sendNotificaDraftEliminato(String toEmail, String tickt, String titolo) {
        if (toEmail == null || toEmail.trim().isEmpty()) {
            System.out.println("[MailService] Notifica eliminazione DRAFT saltata: destinatario vuoto (tickt=" + tickt + ")");
            return;
        }

        String subject = "DRAFT " + nn(tickt) + " eliminato";
        String body = "Il DRAFT " + nn(tickt) +
                      (titolo != null && !titolo.trim().isEmpty() ? " (\"" + titolo.trim() + "\")" : "") +
                      " è stato eliminato dal richiedente prima di essere smistato in SAP.\n";

        if (isDryRun()) {
            System.out.println("========== [MailService] DRY-RUN — notifica eliminazione DRAFT non inviata ==========");
            System.out.println("To:      " + toEmail);
            System.out.println("Subject: " + subject);
            System.out.println("Body:\n" + body);
            System.out.println("==================================================================");
            return;
        }

        try {
            send(toEmail, subject, body, null);
            System.out.println("[MailService] Notifica eliminazione DRAFT inviata a " + toEmail + " per " + tickt);
        } catch (Exception e) {
            System.err.println("[MailService] Errore invio notifica eliminazione DRAFT a " + toEmail + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Notifica che un ticket è ora disponibile sul portale web — inviata al
     * momento della fusione DRAFT → ticket SAP. Distinta e complementare
     * alla comunicazione che parte dal backend SAP (Newton): quella non
     * garantisce che il destinatario sappia che il ticket è consultabile
     * anche via portale. Include link diretto e gli allegati del commento
     * iniziale, se presenti.
     */
    public void sendNotificaTicketDisponibile(String toEmail, String tickt, String titolo,
                                               List<TicketAttachment> allegati) {
        if (toEmail == null || toEmail.trim().isEmpty()) {
            System.out.println("[MailService] Notifica ticket disponibile saltata: destinatario vuoto (tickt=" + tickt + ")");
            return;
        }

        String subject = "Ticket " + nn(tickt) + " disponibile sul portale";
        String body = "Il ticket " + nn(tickt) +
                      (titolo != null && !titolo.trim().isEmpty() ? " (\"" + titolo.trim() + "\")" : "") +
                      " è ora disponibile sul portale di Ticketing WEB.\n";
        String link = buildTicketLink(tickt);
        if (link != null) {
            body += "\nApri il ticket: " + link + "\n";
        }

        if (isDryRun()) {
            System.out.println("========== [MailService] DRY-RUN — notifica ticket disponibile non inviata ==========");
            System.out.println("To:      " + toEmail);
            System.out.println("Subject: " + subject);
            System.out.println("Body:\n" + body);
            System.out.println("Allegati: " + (allegati != null ? allegati.size() : 0));
            System.out.println("==================================================================");
            return;
        }

        try {
            send(toEmail, subject, body, allegati);
            System.out.println("[MailService] Notifica ticket disponibile inviata a " + toEmail + " per " + tickt);
        } catch (Exception e) {
            System.err.println("[MailService] Errore invio notifica ticket disponibile a " + toEmail + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void send(String toEmail, String subject, String body,
                       List<TicketAttachment> allegati) throws MessagingException {

        String host = AppConfig.get("MAIL_HOST", "");
        String port = AppConfig.get("MAIL_PORT", "587");
        String user = AppConfig.get("MAIL_USER", "");
        String pass = AppConfig.get("MAIL_PASS", "");
        String from = AppConfig.get("MAIL_FROM", user);

        Properties props = new Properties();
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.starttls.enable", "true");
        props.put("mail.smtp.host", host);
        props.put("mail.smtp.port", port);

        Session session = Session.getInstance(props, new jakarta.mail.Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(user, pass);
            }
        });

        MimeMessage message = new MimeMessage(session);
        message.setFrom(new InternetAddress(from));
        message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(toEmail));
        message.setSubject(subject, "UTF-8");

        MimeMultipart multipart = new MimeMultipart();

        MimeBodyPart textPart = new MimeBodyPart();
        textPart.setText(body, "UTF-8");
        multipart.addBodyPart(textPart);

        if (allegati != null) {
            for (TicketAttachment a : allegati) {
                if (a.getFileData() == null) continue;
                MimeBodyPart attachPart = new MimeBodyPart();
                ByteArrayDataSource ds = new ByteArrayDataSource(
                    a.getFileData(),
                    a.getMimeType() != null ? a.getMimeType() : "application/octet-stream");
                attachPart.setDataHandler(new DataHandler(ds));
                attachPart.setFileName(a.getFilename());
                multipart.addBodyPart(attachPart);
            }
        }

        message.setContent(multipart);
        Transport.send(message);
    }

    private String nn(String s) { return s != null ? s : ""; }
}