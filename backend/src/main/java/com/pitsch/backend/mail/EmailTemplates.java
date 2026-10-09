package com.pitsch.backend.mail;

import org.springframework.web.util.HtmlUtils;

/** Plain, brand-neutral transactional templates. Every interpolated value is HTML-escaped. */
public final class EmailTemplates {

    private EmailTemplates() { }

    public static MailMessage verifyEmail(String to, String name, String link) {
        return action(to, "Verify your email for Pitsch", "Hi " + name + ",",
                "Confirm your email address to finish setting up Pitsch. This link expires in 48 hours.",
                "Verify email", link);
    }

    public static MailMessage passwordReset(String to, String name, String link) {
        return action(to, "Reset your Pitsch password", "Hi " + name + ",",
                "Someone asked to reset your Pitsch password. If it was you, use the link below within one hour. "
                        + "If not, you can ignore this email — your password stays the same.",
                "Reset password", link);
    }

    public static MailMessage changeEmail(String to, String name, String link) {
        return action(to, "Confirm your new email for Pitsch", "Hi " + name + ",",
                "Confirm this address to use it for your Pitsch account. This link expires in 48 hours.",
                "Confirm email", link);
    }

    public static MailMessage invitation(String to, String inviterName, String workspaceName, String role, String link) {
        return action(to, inviterName + " invited you to " + workspaceName + " on Pitsch", "Hello,",
                inviterName + " invited you to join the " + workspaceName + " workspace on Pitsch as " + role.toLowerCase()
                        + ". The invitation expires in 7 days.",
                "Accept invitation", link);
    }

    public static MailMessage notification(String to, String title, String message, String link) {
        return action(to, "Pitsch: " + title, "", message, "Open in Pitsch", link);
    }

    private static MailMessage action(String to, String subject, String greeting, String body, String cta, String link) {
        String text = (greeting.isEmpty() ? "" : greeting + "\n\n") + body + "\n\n" + cta + ": " + link
                + "\n\n— Pitsch";
        String html = "<div style=\"font-family:system-ui,sans-serif;font-size:15px;line-height:1.5;color:#1f2330;max-width:520px\">"
                + (greeting.isEmpty() ? "" : "<p>" + HtmlUtils.htmlEscape(greeting) + "</p>")
                + "<p>" + HtmlUtils.htmlEscape(body) + "</p>"
                + "<p><a href=\"" + HtmlUtils.htmlEscape(link) + "\" style=\"display:inline-block;padding:10px 16px;"
                + "background:#4f46e5;color:#fff;border-radius:8px;text-decoration:none\">" + HtmlUtils.htmlEscape(cta)
                + "</a></p><p style=\"color:#6b7280;font-size:13px\">Or paste this link: "
                + HtmlUtils.htmlEscape(link) + "</p><p>— Pitsch</p></div>";
        return new MailMessage(to, subject, text, html);
    }
}
