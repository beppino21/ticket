package eone.ticket.web;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;

import eone.ticket.config.AppConfig;
import eone.ticket.service.PromemoriaQuotidianoService;

/**
 * Scheduler interno alla webapp per il promemoria quotidiano AMS/DISPATCHER
 * (vedi PromemoriaQuotidianoService). Parte all'avvio di Tomcat, calcola
 * l'attesa fino al prossimo orario configurato in un giorno feriale
 * (lun-ven) e si riprogramma da solo dopo ogni esecuzione — nessuna
 * schedulazione esterna necessaria.
 *
 * NOTA — limite noto di questo approccio: se Tomcat è fermo (restart,
 * deploy, manutenzione) esattamente nella finestra dell'orario configurato,
 * l'invio di quel giorno viene saltato — riparte regolarmente dal giorno
 * feriale successivo. Non c'è un "recupero" del giorno perso.
 *
 * Configurazione (config.properties):
 *   PROMEMORIA_QUOTIDIANO_ABILITATO=true|false  (default true — letto anche
 *                                                 a runtime da PromemoriaQuotidianoService,
 *                                                 qui serve solo a evitare di
 *                                                 far partire lo scheduler se inutile)
 *   PROMEMORIA_QUOTIDIANO_ORA=8       (default 8)
 *   PROMEMORIA_QUOTIDIANO_MINUTO=30   (default 30)
 */
public class PromemoriaSchedulerListener implements ServletContextListener {

    private ScheduledExecutorService executor;

    @Override
    public void contextInitialized(ServletContextEvent sce) {
        if (!AppConfig.getBoolean("PROMEMORIA_QUOTIDIANO_ABILITATO", true)) {
            System.out.println("[PromemoriaSchedulerListener] Disabilitato da configurazione — scheduler non avviato.");
            return;
        }

        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "promemoria-quotidiano-scheduler");
            t.setDaemon(true);
            return t;
        });

        long delaySeconds = secondsUntilProssimoOrarioFeriale();
        System.out.println("[PromemoriaSchedulerListener] Scheduler avviato — prossimo invio tra " +
            (delaySeconds / 3600) + "h " + ((delaySeconds % 3600) / 60) + "m.");
        executor.schedule(this::eseguiERiprogramma, delaySeconds, TimeUnit.SECONDS);
    }

    private void eseguiERiprogramma() {
        try {
            System.out.println("[PromemoriaSchedulerListener] Avvio invio promemoria quotidiano...");
            new PromemoriaQuotidianoService().eseguiPromemoriaQuotidiano();
        } catch (Exception e) {
            System.err.println("[PromemoriaSchedulerListener] Errore durante l'invio: " + e.getMessage());
            e.printStackTrace();
        } finally {
            if (executor != null && !executor.isShutdown()) {
                long delaySeconds = secondsUntilProssimoOrarioFeriale();
                executor.schedule(this::eseguiERiprogramma, delaySeconds, TimeUnit.SECONDS);
            }
        }
    }

    /** Secondi da adesso al prossimo orario configurato, saltando sabato/domenica. */
    private long secondsUntilProssimoOrarioFeriale() {
        int ora;
        int minuto;
        try {
            ora = Integer.parseInt(AppConfig.get("PROMEMORIA_QUOTIDIANO_ORA", "8").trim());
        } catch (NumberFormatException e) { ora = 8; }
        try {
            minuto = Integer.parseInt(AppConfig.get("PROMEMORIA_QUOTIDIANO_MINUTO", "30").trim());
        } catch (NumberFormatException e) { minuto = 30; }

        LocalDateTime adesso = LocalDateTime.now();
        LocalTime orarioTarget = LocalTime.of(ora, minuto);
        LocalDateTime candidato = LocalDateTime.of(adesso.toLocalDate(), orarioTarget);
        if (!candidato.isAfter(adesso)) {
            candidato = candidato.plusDays(1); // orario di oggi già passato -> domani
        }
        while (candidato.getDayOfWeek() == DayOfWeek.SATURDAY || candidato.getDayOfWeek() == DayOfWeek.SUNDAY) {
            candidato = candidato.plusDays(1);
        }
        long seconds = ChronoUnit.SECONDS.between(adesso, candidato);
        return Math.max(seconds, 1L);
    }

    @Override
    public void contextDestroyed(ServletContextEvent sce) {
        if (executor != null) {
            executor.shutdownNow();
        }
    }
}
