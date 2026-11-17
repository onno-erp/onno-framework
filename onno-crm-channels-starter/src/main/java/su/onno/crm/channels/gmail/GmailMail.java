package su.onno.crm.channels.gmail;

import jakarta.mail.*;
import jakarta.mail.internet.*;
import java.io.*;
import java.time.Instant;
import java.util.*;

/**
 * Parses Gmail MIME. The plain-text body is always kept; an HTML part is kept too, but only after
 * {@link EmailHtml#sanitize} strips scripts, handlers and unsafe URLs. No attachment content runs.
 */
final class GmailMail {
    /**
     * A parsed email. {@code files} carry their bytes; {@code remote} files stay in Gmail and are
     * only described ({@link #readFull}).
     */
    record Incoming(String sender,String name,String replyTo,String subject,String messageId,String text,Instant sentAt,List<Outgoing> files,String html,List<RemoteFile> remote) {
        Incoming(String sender,String name,String replyTo,String subject,String messageId,String text,Instant sentAt,List<Outgoing> files,String html) {
            this(sender,name,replyTo,subject,messageId,text,sentAt,files,html,List.of());
        }
        Incoming(String sender,String name,String replyTo,String subject,String messageId,String text,Instant sentAt,List<Outgoing> files) {
            this(sender,name,replyTo,subject,messageId,text,sentAt,files,null);
        }
    }
    /** A file left in Gmail: the message part that holds it, and what it is announced as. */
    record RemoteFile(String partId,String filename,String contentType,long size,String contentId) {}
    static Incoming read(String raw) throws Exception {
        var message=new MimeMessage(Session.getInstance(new Properties()),new ByteArrayInputStream(Base64.getUrlDecoder().decode(raw)));
        var from=(InternetAddress)message.getFrom()[0];
        var reply=(InternetAddress)message.getReplyTo()[0];
        var files=new ArrayList<Outgoing>();collect(message,files);
        return new Incoming(from.getAddress(),from.getPersonal()==null?from.getAddress():from.getPersonal(),reply.getAddress(),
            Objects.toString(message.getSubject(),"(No subject)"),Objects.toString(message.getMessageID(),""),nonEmptyBody(body(message)),
            message.getSentDate()==null?Instant.now():message.getSentDate().toInstant(),List.copyOf(files),EmailHtml.sanitize(html(message)));
    }
    /**
     * Parses Gmail's {@code format=full} answer the way {@link #read} parses raw MIME, without the
     * files' bytes: Gmail sends a file as a reference ({@code attachmentId}), so an email with a
     * 20 MB brochure costs only its text. The files come back as {@link RemoteFile}s.
     */
    static Incoming readFull(com.fasterxml.jackson.databind.JsonNode message) throws Exception {
        var payload=message.path("payload");
        var from=address(header(payload,"From"));
        if(from==null)throw new MessagingException("Email has no sender");
        var reply=address(header(payload,"Reply-To"));
        if(reply==null)reply=from;
        var remote=new ArrayList<RemoteFile>();collectRemote(payload,remote);
        String subject=header(payload,"Subject");
        return new Incoming(from.getAddress(),from.getPersonal()==null?from.getAddress():from.getPersonal(),reply.getAddress(),
            subject==null?"(No subject)":MimeUtility.decodeText(subject),Objects.toString(header(payload,"Message-ID"),""),nonEmptyBody(fullBody(payload)),
            message.hasNonNull("internalDate")?Instant.ofEpochMilli(message.path("internalDate").asLong()):Instant.now(),
            List.of(),EmailHtml.sanitize(fullHtml(payload)),List.copyOf(remote));
    }
    private static String header(com.fasterxml.jackson.databind.JsonNode part,String name) {
        for(var header:part.path("headers"))if(header.path("name").asText().equalsIgnoreCase(name))return header.path("value").asText();
        return null;
    }
    private static InternetAddress address(String value) {
        if(value==null||value.isBlank())return null;
        try{var parsed=InternetAddress.parseHeader(value,false);return parsed.length==0?null:parsed[0];}
        catch(AddressException e){return null;}
    }
    private static String mimeType(com.fasterxml.jackson.databind.JsonNode part) { return part.path("mimeType").asText("").toLowerCase(Locale.ROOT); }
    private static String disposition(com.fasterxml.jackson.databind.JsonNode part) {
        String value=header(part,"Content-Disposition");
        if(value==null)return "";
        int semi=value.indexOf(';');
        return (semi<0?value:value.substring(0,semi)).strip().toLowerCase(Locale.ROOT);
    }
    private static String filename(com.fasterxml.jackson.databind.JsonNode part) {
        String name=part.path("filename").asText("");
        return name.isBlank()?null:name;
    }
    /** The same test as {@link #collect}: an attachment, a named part, or an inline non-text part. */
    private static boolean isFile(com.fasterxml.jackson.databind.JsonNode part) {
        String type=mimeType(part);
        return !type.startsWith("multipart/") && (disposition(part).equals("attachment") || filename(part)!=null
            || disposition(part).equals("inline") && !type.startsWith("text/"));
    }
    /**
     * A text part's content. Gmail converts text to UTF-8 in this format whatever charset the part
     * declares (a {@code windows-1252} part arrives as UTF-8), so UTF-8 is read first; the declared
     * charset is used only for bytes that are not valid UTF-8.
     */
    private static String decode(com.fasterxml.jackson.databind.JsonNode part) {
        String data=part.path("body").path("data").asText("");
        if(data.isEmpty())return part.path("body").hasNonNull("attachmentId")?"[Message content unavailable]":"";
        byte[] bytes=Base64.getUrlDecoder().decode(data.replace('+','-').replace('/','_').replace("=",""));
        try {
            return java.nio.charset.StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        } catch(java.nio.charset.CharacterCodingException notUtf8) {
            String type=header(part,"Content-Type");
            try {
                String declared=type==null?null:new ContentType(type).getParameter("charset");
                if(declared!=null&&!declared.isBlank())return new String(bytes,java.nio.charset.Charset.forName(MimeUtility.javaCharset(declared.strip())));
            } catch(Exception unknownCharset) { /* fall through */ }
            return new String(bytes,java.nio.charset.StandardCharsets.UTF_8);
        }
    }
    private static String fullBody(com.fasterxml.jackson.databind.JsonNode part) {
        if(disposition(part).equals("attachment"))return "[Attachment: "+Objects.toString(filename(part),"file")+"]";
        String type=mimeType(part);
        if(type.equals("text/plain"))return decode(part);
        if(type.startsWith("multipart/")) {
            if(type.equals("multipart/alternative"))
                for(var child:part.path("parts"))if(mimeType(child).equals("text/plain"))return fullBody(child);
            var text=new StringBuilder();for(var child:part.path("parts"))text.append(fullBody(child)).append('\n');return text.toString();
        }
        if(type.equals("text/html"))return EmailHtml.toText(decode(part));
        if(disposition(part).equals("inline") || header(part,"Content-ID")!=null)return "";
        return "[Message content unavailable]";
    }
    private static String fullHtml(com.fasterxml.jackson.databind.JsonNode part) {
        if(disposition(part).equals("attachment"))return null;
        String type=mimeType(part);
        if(type.equals("text/html"))return decode(part);
        if(type.startsWith("multipart/"))for(var child:part.path("parts")){String found=fullHtml(child);if(found!=null)return found;}
        return null;
    }
    private static void collectRemote(com.fasterxml.jackson.databind.JsonNode part,List<RemoteFile> files) {
        if(mimeType(part).startsWith("multipart/")){for(var child:part.path("parts"))collectRemote(child,files);return;}
        if(!isFile(part) || files.size()>=10)return;
        long size=part.path("body").path("size").asLong(0);
        if(size==0)return;
        String cid=header(part,"Content-ID");
        files.add(new RemoteFile(part.path("partId").asText(""),Objects.toString(filename(part),"attachment"),
            mimeType(part).isBlank()?"application/octet-stream":mimeType(part),size,cid==null?null:cid.strip().replaceAll("^<|>$","")));
    }
    private static void collect(Part part,List<Outgoing> files) throws Exception {
        if(part.isMimeType("multipart/*")) {
            var multi=(Multipart)part.getContent();
            for(int i=0;i<multi.getCount();i++)collect(multi.getBodyPart(i),files);
        } else if(Part.ATTACHMENT.equalsIgnoreCase(part.getDisposition()) || part.getFileName()!=null
                || Part.INLINE.equalsIgnoreCase(part.getDisposition()) && !part.isMimeType("text/*")) {
            if(files.size()>=10)return;
            try(var stream=part.getInputStream()) {
                byte[] bytes=stream.readNBytes(20*1024*1024+1);
                if(bytes.length==0 || bytes.length>20*1024*1024)return;
                String filename=MimeUtility.decodeText(Objects.toString(part.getFileName(),"attachment"));
                String[] cid=part.getHeader("Content-ID");
                files.add(new Outgoing(filename,new ContentType(part.getContentType()).getBaseType(),bytes,
                    cid==null||cid.length==0?null:cid[0].strip().replaceAll("^<|>$","")));
            }
        }
    }
    /** The first inline text/html part, preferring the HTML alternative of a multipart/alternative. */
    private static String html(Part part) throws Exception {
        if(Part.ATTACHMENT.equalsIgnoreCase(part.getDisposition()))return null;
        if(part.isMimeType("text/html"))return part.getContent().toString();
        if(part.isMimeType("multipart/*")) {
            var multi=(Multipart)part.getContent();
            for(int i=0;i<multi.getCount();i++){String found=html(multi.getBodyPart(i));if(found!=null)return found;}
        }
        return null;
    }
    private static String nonEmptyBody(String value) { String text=value.strip();return text.isBlank()?"[Empty email]":text; }
    private static String body(Part part) throws Exception {
        if(Part.ATTACHMENT.equalsIgnoreCase(part.getDisposition()))return "[Attachment: "+MimeUtility.decodeText(Objects.toString(part.getFileName(),"file"))+"]";
        if(part.isMimeType("text/plain"))return part.getContent().toString();
        if(part.isMimeType("multipart/*")) {
            Multipart multipart=(Multipart)part.getContent();
            if(part.isMimeType("multipart/alternative")) {
                for(int i=0;i<multipart.getCount();i++)if(multipart.getBodyPart(i).isMimeType("text/plain"))return body(multipart.getBodyPart(i));
            }
            StringBuilder text=new StringBuilder();for(int i=0;i<multipart.getCount();i++)text.append(body(multipart.getBodyPart(i))).append('\n');return text.toString();
        }
        if(part.isMimeType("text/html"))return EmailHtml.toText(part.getContent().toString());
        // An inline image (signature logo, cid: picture) is imported as a file, not body text.
        if(Part.INLINE.equalsIgnoreCase(part.getDisposition()) || part.getHeader("Content-ID")!=null)return "";
        return "[Message content unavailable]";
    }
    /**
     * One file to attach: the bytes, and the name and type they are announced under. {@code contentId}
     * is the inbound part's Content-ID (without angle brackets), so {@code cid:} images in the HTML
     * body can be pointed at the stored copy; null for outbound files and parts without one.
     */
    record Outgoing(String filename,String contentType,byte[] content,String contentId) {
        Outgoing(String filename,String contentType,byte[] content) { this(filename,contentType,content,null); }
    }
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
        return reply(from,to,subject,reference,body,files,id,null);
    }
    /** {@link #reply(String,String,String,String,String,List,UUID)} signed with {@code signatureHtml} (see {@link #compose(String,String,String,String,String,List,UUID,String)}). */
    static String reply(String from,String to,String subject,String reference,String body,
            List<Outgoing> files,UUID id,String signatureHtml) throws Exception {
        return compose(from,to,subject.regionMatches(true,0,"Re:",0,3)?subject:"Re: "+subject,reference,body,files,id,signatureHtml);
    }
    static String compose(String from,String to,String subject,String reference,String body,
            List<Outgoing> files,UUID id) throws Exception {
        return compose(from,to,subject,reference,body,files,id,null);
    }
    /**
     * An email whose text is followed by the sender's signature. With a signature the text becomes a
     * {@code multipart/alternative}: a plain-text part (the text, the {@code "-- "} separator and the
     * signature as text) and an HTML part (the text, escaped, and the signature HTML as Gmail writes
     * it, logo included). Without one ({@code null} or blank) the email is plain text, as before.
     */
    static String compose(String from,String to,String subject,String reference,String body,
            List<Outgoing> files,UUID id,String signatureHtml) throws Exception {
        if(subject.contains("\r")||subject.contains("\n"))throw new IllegalArgumentException("Invalid email subject");
        if(from.contains("\r")||from.contains("\n")||to.contains("\r")||to.contains("\n")||reference.contains("\r")||reference.contains("\n"))throw new IllegalArgumentException("Invalid email header");
        var message=new MimeMessage(Session.getInstance(new Properties()));
        message.setFrom(new InternetAddress(from,true));message.setRecipient(Message.RecipientType.TO,new InternetAddress(to,true));
        message.setSubject(subject,"UTF-8");
        String signature=signatureHtml==null||signatureHtml.isBlank()?null:signatureHtml;
        if(files.isEmpty())setText(message,body,signature);
        else {
            var parts=new MimeMultipart("mixed");
            var text=new MimeBodyPart();setText(text,body,signature);parts.addBodyPart(text);
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
    /** The email's text: plain, or plain and HTML alternatives when it is signed. */
    private static void setText(MimePart part,String body,String signature) throws Exception {
        if(signature==null){part.setText(body,"UTF-8");return;}
        var alternatives=new MimeMultipart("alternative");
        var plain=new MimeBodyPart();
        String signatureText=EmailHtml.toText(signature);
        plain.setText(signatureText.isBlank()?body:body+"\n\n-- \n"+signatureText,"UTF-8");
        alternatives.addBodyPart(plain);
        var html=new MimeBodyPart();
        html.setText(signedHtml(body,signature),"UTF-8","html");
        alternatives.addBodyPart(html);
        part.setContent(alternatives);
    }
    /** The typed text as HTML (escaped, line breaks kept) followed by the signature, as Gmail lays it out. */
    static String signedHtml(String body,String signature) {
        String text=org.jsoup.nodes.Entities.escape(body).replace("\r\n","\n").replace("\n","<br>");
        return "<div dir=\"auto\">"+text+"</div><br><div class=\"gmail_signature\" data-smartmail=\"gmail_signature\">"
            +signature+"</div>";
    }
    private static String safeName(String name) {
        String leaf=name==null||name.isBlank()?"attachment":name.replaceAll("[\\r\\n\"\\\\/]","_").strip();
        return leaf.isBlank()?"attachment":leaf.length()<=120?leaf:leaf.substring(0,120);
    }
}
