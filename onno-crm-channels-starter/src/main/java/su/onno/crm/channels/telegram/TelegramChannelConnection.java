package su.onno.crm.channels.telegram;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Properties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import su.onno.crm.service.CrmChannelConnection;

/** Local-demo credential persistence in the server-owned external Spring properties file. */
@Component
@ConditionalOnProperty(name="onno.crm.channels.telegram.enabled",havingValue="true")
public class TelegramChannelConnection implements CrmChannelConnection {
    private final TelegramInboxBridge bridge;
    private final ObjectMapper json;
    private final Path credentialFile;
    public su.onno.crm.service.CrmChannelDefinition definition() { return new su.onno.crm.service.CrmChannelDefinition("TELEGRAM","Telegram","/crm/channels/telegram.png"); }
    public TelegramChannelConnection(TelegramInboxBridge bridge,ObjectMapper json,
            @Value("${spring.config.additional-location:}") String location) {
        this.bridge=bridge;this.json=json;
        this.credentialFile=location.startsWith("file:") && location.endsWith(".properties") && !location.contains(",")
                ? Path.of(location.substring(5)).toAbsolutePath() : null;
    }
    public String key() { return "telegram"; }
    public View view() { return bridge.channelView(credentialFile!=null); }
    public void command(String action,String credential) {
        try {
            switch(action) {
                case "check" -> bridge.checkConnection();
                case "pause" -> bridge.setChannelActive(false);
                case "resume" -> {bridge.checkConnection();bridge.setChannelActive(true);}
                case "reconnect" -> {
                    if(credentialFile==null || credential==null || !credential.matches("[0-9]+:[A-Za-z0-9_-]{20,}"))
                        throw new IllegalArgumentException("Enter a valid Telegram bot token");
                    bridge.reconnect(new TelegramClient(credential,json),()->persist(credential));
                }
                default -> throw new IllegalArgumentException("Unsupported Telegram action");
            }
        } catch(TelegramClient.ApiFailure error) {
            throw new IllegalArgumentException("Could not verify Telegram. Check the bot token and connection, then try again.");
        }
    }
    private void persist(String token) {
        Path temporary=null;
        try {
            if(Files.isSymbolicLink(credentialFile) || !Files.isRegularFile(credentialFile))throw new IOException();
            Properties properties=new Properties();
            try(var input=Files.newInputStream(credentialFile)){properties.load(input);}
            properties.setProperty("onno.crm.channels.telegram.token",token);
            temporary=Files.createTempFile(credentialFile.getParent(),".crm-credentials-",".properties",PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            try(var output=Files.newOutputStream(temporary)){properties.store(output,"CRM channel credentials");}
            Files.move(temporary,credentialFile,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        } catch(IOException | UnsupportedOperationException error) {
            throw new IllegalArgumentException("Could not save credentials to the configured server file");
        } finally {
            if(temporary!=null)try{Files.deleteIfExists(temporary);}catch(IOException ignored){}
        }
    }
}
