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

        if (toEmail == null || toEmail.trim().isEmpty()) {
            System.out.println("[MailService] Destinatario vuoto, notifica saltata (ticket " + tickt + ")");
            return;
        }

        String testoBreve = testoCompleto != null && testoCompleto.length() > 100
            ? testoCompleto.substring(0, 97) + "..." : testoCompleto;
        String subject = "Ticket " + tickt + " — " + statoLabel + " — " + nn(testoBreve);
        String body = buildBody(tickt, statoLabel, autoreId, testoCompleto, allegati);

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
        StringBuilder sb = new StringBuilder();
        sb.append("Ticket: ").append(tickt).append("\n");
        sb.append("Stato:  ").append(statoLabel).append("\n");
        sb.append("Autore: ").append(nn(autoreId)).append("\n");
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