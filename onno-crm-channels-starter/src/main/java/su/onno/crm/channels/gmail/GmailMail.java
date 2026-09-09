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
    static String reply(String from,String to,String subject,String reference,String body,UUID id) throws Exception {
        if(from.contains("\r")||from.contains("\n")||to.contains("\r")||to.contains("\n")||reference.contains("\r")||reference.contains("\n"))throw new IllegalArgumentException("Invalid email header");
        var message=new MimeMessage(Session.getInstance(new Properties()));
        message.setFrom(new InternetAddress(from,true));message.setRecipient(Message.RecipientType.TO,new InternetAddress(to,true));
        message.setSubject(subject.startsWith("Re:")?subject:"Re: "+subject,"UTF-8");message.setText(body,"UTF-8");
        if(!reference.isBlank()){message.setHeader("In-Reply-To",reference);message.setHeader("References",reference);}
        message.setSentDate(new Date());message.saveChanges();message.setHeader("Message-ID","<onno-"+id+"@"+from.substring(from.indexOf('@')+1)+">");
        var out=new ByteArrayOutputStream();message.writeTo(out);return Base64.getUrlEncoder().withoutPadding().encodeToString(out.toByteArray());
    }
}
