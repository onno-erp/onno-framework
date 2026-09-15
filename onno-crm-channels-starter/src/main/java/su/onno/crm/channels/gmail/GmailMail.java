package su.onno.crm.channels.gmail;

import jakarta.mail.*;
import jakarta.mail.internet.*;
import java.io.*;
import java.time.Instant;
import java.util.*;

/** MIME stays plain text in the CRM; no remote HTML or attachment content is executed. */
final class GmailMail {
    record Incoming(String sender,String name,String replyTo,String subject,String messageId,String text,Instant sentAt) {}
    static Incoming read(String raw) throws Exception {
        var message=new MimeMessage(Session.getInstance(new Properties()),new ByteArrayInputStream(Base64.getUrlDecoder().decode(raw)));
        var from=(InternetAddress)message.getFrom()[0];
        var reply=(InternetAddress)message.getReplyTo()[0];
        return new Incoming(from.getAddress(),from.getPersonal()==null?from.getAddress():from.getPersonal(),reply.getAddress(),
            Objects.toString(message.getSubject(),"(No subject)"),Objects.toString(message.getMessageID(),""),nonEmptyBody(body(message)),
            message.getSentDate()==null?Instant.now():message.getSentDate().toInstant());
    }
    private static String nonEmptyBody(String value) { String text=value.strip();return text.isBlank()?"[Empty email]":text; }
    private static String body(Part part) throws Exception {
        if(Part.ATTACHMENT.equalsIgnoreCase(part.getDisposition()))return "[Attachment: "+Objects.toString(part.getFileName(),"file")+"]";
        if(part.isMimeType("text/plain"))return part.getContent().toString();
        if(part.isMimeType("multipart/*")) {
            Multipart multipart=(Multipart)part.getContent();
            if(part.isMimeType("multipart/alternative")) {
                for(int i=0;i<multipart.getCount();i++)if(multipart.getBodyPart(i).isMimeType("text/plain"))return body(multipart.getBodyPart(i));
            }
            StringBuilder text=new StringBuilder();for(int i=0;i<multipart.getCount();i++)text.append(body(multipart.getBodyPart(i))).append('\n');return text.toString();
        }
        if(part.isMimeType("text/html"))return part.getContent().toString().replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>","").replaceAll("(?is)<[^>]+>"," ").replace("&nbsp;"," ").replace("&amp;","&");
        return "[Message content unavailable]";
    }
    /** One file to attach: the bytes, and the name and type they are announced under. */
    record Outgoing(String filename,String contentType,byte[] content) {}
    static String reply(String from,String to,String subject,String reference,String body,UUID id) throws Exception {
        return reply(from,to,subject,reference,body,List.of(),id);
    }
    /**
     * A plain-text reply, carrying files when there are any. With attachments the body becomes the
     * first part of a {@code multipart/mixed} message rather than the whole message, which is what
     * every mail client expects; with none the message is left as plain text exactly as before.
     */
    static String reply(String from,String to,String subject,String reference,String body,
            List<Outgoing> files,UUID id) throws Exception {
        if(from.contains("\r")||from.contains("\n")||to.contains("\r")||to.contains("\n")||reference.contains("\r")||reference.contains("\n"))throw new IllegalArgumentException("Invalid email header");
        var message=new MimeMessage(Session.getInstance(new Properties()));
        message.setFrom(new InternetAddress(from,true));message.setRecipient(Message.RecipientType.TO,new InternetAddress(to,true));
        message.setSubject(subject.startsWith("Re:")?subject:"Re: "+subject,"UTF-8");
        if(files.isEmpty())message.setText(body,"UTF-8");
        else {
            var parts=new MimeMultipart("mixed");
            var text=new MimeBodyPart();text.setText(body,"UTF-8");parts.addBodyPart(text);
            for(var file:files) {
                var part=new MimeBodyPart();
                part.setDataHandler(new jakarta.activation.DataHandler(
                    new jakarta.mail.util.ByteArrayDataSource(file.content(),
                        file.contentType()==null||file.contentType().isBlank()?"application/octet-stream":file.contentType())));
                // Encoded per RFC 2231, so a name with spaces or accents survives the header intact.
                part.setFileName(MimeUtility.encodeText(safeName(file.filename()),"UTF-8","B"));
                part.setDisposition(Part.ATTACHMENT);
                parts.addBodyPart(part);
            }
            message.setContent(parts);
        }
        if(!reference.isBlank()){message.setHeader("In-Reply-To",reference);message.setHeader("References",reference);}
        message.setSentDate(new Date());message.saveChanges();message.setHeader("Message-ID","<onno-"+id+"@"+from.substring(from.indexOf('@')+1)+">");
        var out=new ByteArrayOutputStream();message.writeTo(out);return Base64.getUrlEncoder().withoutPadding().encodeToString(out.toByteArray());
    }
    private static String safeName(String name) {
        String leaf=name==null||name.isBlank()?"attachment":name.replaceAll("[\\r\\n\"\\\\/]","_").strip();
        return leaf.isBlank()?"attachment":leaf.length()<=120?leaf:leaf.substring(0,120);
    }
}
