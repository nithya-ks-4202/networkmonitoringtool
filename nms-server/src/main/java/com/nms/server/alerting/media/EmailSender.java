package com.nms.server.alerting.media;

import com.nms.server.domain.Alert;
import com.nms.server.domain.MediaType;
import com.nms.server.domain.MediaTypeKind;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import java.io.UnsupportedEncodingException;
import java.util.Properties;

/**
 * Sends alerts by email.
 *
 * <p>SMTP settings come from the media type rather than from application
 * configuration, because in a hosted deployment each tenant relays through
 * their own server -- often one that only accepts mail from their own network
 * or with their own credentials.
 */
@Component
public class EmailSender implements MediaSender {

    private static final Logger log = LoggerFactory.getLogger(EmailSender.class);

    @Override
    public MediaTypeKind kind() {
        return MediaTypeKind.EMAIL;
    }

    @Override
    public void send(Alert alert, MediaType mediaType) throws DeliveryException {
        String recipient = alert.getSendTo();
        if (recipient == null || recipient.isBlank()) {
            throw new PermanentDeliveryException("no recipient address configured for this user");
        }

        JavaMailSenderImpl mailSender = buildMailSender(mediaType);

        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");

            String fromAddress = mediaType.configString("fromAddress", "monitoring@localhost");
            String fromName = mediaType.configString("fromName", "");
            if (fromName.isBlank()) {
                helper.setFrom(fromAddress);
            } else {
                helper.setFrom(new InternetAddress(fromAddress, fromName, "UTF-8"));
            }

            // Several addresses per user is normal -- a person and their team
            // alias -- so the field is split rather than treated as one.
            helper.setTo(recipient.split("\\s*[,;]\\s*"));
            helper.setSubject(alert.getSubject());
            helper.setText(alert.getMessage(), false);

            mailSender.send(message);
            log.debug("Email delivered to {} for problem {}", recipient,
                    alert.getProblem() == null ? "?" : alert.getProblem().getId());

        } catch (MailAuthenticationException e) {
            // Credentials will not fix themselves; retrying just locks the
            // account out and delays every other alert behind it.
            throw new PermanentDeliveryException("SMTP authentication failed: " + e.getMessage(), e);
        } catch (MailSendException e) {
            throw new TransientDeliveryException("SMTP delivery failed: " + e.getMessage(), e);
        } catch (MessagingException | UnsupportedEncodingException e) {
            throw new PermanentDeliveryException("could not compose the message: " + e.getMessage(), e);
        } catch (RuntimeException e) {
            throw new TransientDeliveryException("unexpected mail failure: " + e.getMessage(), e);
        }
    }

    private JavaMailSenderImpl buildMailSender(MediaType mediaType) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(mediaType.configString("smtpHost", "localhost"));
        sender.setPort(mediaType.configInt("smtpPort", 25));
        sender.setDefaultEncoding("UTF-8");

        String username = mediaType.configString("smtpUsername", "");
        if (!username.isBlank()) {
            sender.setUsername(username);
            sender.setPassword(mediaType.configString("smtpPassword", ""));
        }

        Properties properties = sender.getJavaMailProperties();
        properties.put("mail.transport.protocol", "smtp");
        properties.put("mail.smtp.auth", String.valueOf(!username.isBlank()));

        // Timeouts matter more here than anywhere else in the product: an SMTP
        // relay that accepts the connection and then stalls would otherwise
        // hold an alerter thread indefinitely while an incident escalates.
        int timeout = mediaType.getTimeoutSeconds() * 1000;
        properties.put("mail.smtp.connectiontimeout", timeout);
        properties.put("mail.smtp.timeout", timeout);
        properties.put("mail.smtp.writetimeout", timeout);

        String security = mediaType.configString("smtpSecurity", "NONE").toUpperCase();
        switch (security) {
            case "STARTTLS" -> {
                properties.put("mail.smtp.starttls.enable", "true");
                properties.put("mail.smtp.starttls.required", "true");
            }
            case "SSL", "TLS" -> {
                properties.put("mail.smtp.ssl.enable", "true");
                properties.put("mail.smtp.socketFactory.port", sender.getPort());
                properties.put("mail.smtp.socketFactory.class", "javax.net.ssl.SSLSocketFactory");
            }
            default -> properties.put("mail.smtp.starttls.enable", "false");
        }

        if (mediaType.configString("smtpVerifyCertificate", "true").equalsIgnoreCase("false")) {
            // Opt-in only. Internal relays with self-signed certificates are
            // common enough that refusing them outright would push people to
            // disable TLS entirely, which is strictly worse.
            properties.put("mail.smtp.ssl.trust", "*");
        }

        return sender;
    }
}
