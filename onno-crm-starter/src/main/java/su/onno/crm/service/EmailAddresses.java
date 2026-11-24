package su.onno.crm.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Email address lists as the CRM keeps them on a message ({@code ConversationMessage.sentTo}/{@code copyTo}): RFC 5322
 * mailboxes, {@code "Name <address>"} or a bare address, joined by {@code ", "}. A name that holds a comma or another
 * special character is quoted, so a stored list splits back into the same entries.
 */
public final class EmailAddresses {
    /** The most people a reply may copy. */
    public static final int MAX_CC = 20;
    /** The stored length of one list ({@code sentTo}, {@code copyTo}). */
    public static final int MAX_LIST_LENGTH = 4000;

    private static final Pattern ADDRESS = Pattern.compile(
            "[\\p{L}\\p{N}.!#$%&'*+/=?^_`{|}~-]+@[\\p{L}\\p{N}](?:[\\p{L}\\p{N}-]*[\\p{L}\\p{N}])?(?:\\.[\\p{L}\\p{N}](?:[\\p{L}\\p{N}-]*[\\p{L}\\p{N}])?)+");
    private static final String SPECIALS = "()<>[]:;@\\,.\"";

    private EmailAddresses() {}

    /** The entries of a stored list, in order; empty for null or blank. Commas inside quotes or angle brackets do not split. */
    public static List<String> split(String list) {
        var entries = new ArrayList<String>();
        if (list == null || list.isBlank()) return entries;
        var current = new StringBuilder();
        boolean quoted = false, escaped = false;
        int angle = 0;
        for (char c : list.toCharArray()) {
            if (escaped) { current.append(c); escaped = false; continue; }
            if (quoted && c == '\\') { current.append(c); escaped = true; continue; }
            if (c == '"') quoted = !quoted;
            else if (!quoted && c == '<') angle++;
            else if (!quoted && c == '>' && angle > 0) angle--;
            if ((c == ',' || c == ';') && !quoted && angle == 0) {
                if (!current.toString().isBlank()) entries.add(current.toString().strip());
                current.setLength(0);
                continue;
            }
            current.append(c);
        }
        if (!current.toString().isBlank()) entries.add(current.toString().strip());
        return entries;
    }

    /** The bare address of one entry ({@code "Name <a@b>"} gives {@code a@b}). */
    public static String address(String entry) {
        String text = entry == null ? "" : entry.strip();
        int open = text.lastIndexOf('<'), close = text.lastIndexOf('>');
        return (open >= 0 && close > open ? text.substring(open + 1, close) : text).strip();
    }

    /** The name of one entry, unquoted; null when it has none. */
    public static String name(String entry) {
        String text = entry == null ? "" : entry.strip();
        int open = text.lastIndexOf('<');
        if (open <= 0) return null;
        String name = text.substring(0, open).strip();
        if (name.length() >= 2 && name.startsWith("\"") && name.endsWith("\""))
            name = name.substring(1, name.length() - 1).replace("\\\"", "\"").replace("\\\\", "\\");
        return name.isBlank() ? null : name;
    }

    /** Whether {@code address} is one syntactically valid bare email address. */
    public static boolean valid(String address) {
        return address != null && address.length() <= 320 && ADDRESS.matcher(address).matches();
    }

    /**
     * One entry checked and written the stored way ({@code Name <address>}, the name quoted when it needs it).
     *
     * @throws IllegalArgumentException when it is not exactly one valid address, or holds a line break or control character
     */
    public static String normalize(String entry) {
        if (entry == null || entry.isBlank()) throw new IllegalArgumentException("Enter an email address");
        if (entry.length() > 640 || entry.chars().anyMatch(c -> c < 0x20 || c == 0x7f))
            throw new IllegalArgumentException("Invalid email address");
        String text = entry.strip();
        // "Name <address>" is one entry even when the name holds an unquoted comma; anything else must not split.
        boolean named = text.endsWith(">") && text.indexOf('<') == text.lastIndexOf('<') && text.indexOf('>') == text.length() - 1;
        if (!named && split(entry).size() != 1) throw new IllegalArgumentException("Enter one email address per entry");
        String address = address(entry);
        if (!valid(address)) throw new IllegalArgumentException("Invalid email address: " + address);
        String name = name(entry);
        if (name == null) return address;
        if (name.indexOf('<') >= 0 || name.indexOf('>') >= 0) throw new IllegalArgumentException("Invalid email address");
        boolean quote = name.chars().anyMatch(c -> SPECIALS.indexOf(c) >= 0);
        return (quote ? "\"" + name.replace("\\", "\\\\").replace("\"", "\\\"") + "\"" : name) + " <" + address + ">";
    }

    /**
     * A reply's Cc: each entry normalized, duplicates (by address, ignoring case) and {@code excluded} addresses
     * dropped, at most {@link #MAX_CC}.
     *
     * @throws IllegalArgumentException for an invalid entry or too many
     */
    public static List<String> cc(List<String> entries, String... excluded) {
        if (entries == null || entries.isEmpty()) return List.of();
        if (entries.size() > MAX_CC) throw new IllegalArgumentException("Copy at most " + MAX_CC + " people");
        Set<String> seen = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (String other : excluded) if (other != null && !other.isBlank()) seen.add(address(other));
        var result = new ArrayList<String>();
        for (String entry : entries) {
            String normalized = normalize(entry);
            if (seen.add(address(normalized))) result.add(normalized);
        }
        return List.copyOf(result);
    }

    /** The entries joined the stored way, whole entries only up to {@link #MAX_LIST_LENGTH}; null when there are none. */
    public static String join(List<String> entries) {
        if (entries == null || entries.isEmpty()) return null;
        var joined = new StringBuilder();
        for (String entry : entries) {
            if (entry == null || entry.isBlank()) continue;
            String next = entry.strip();
            if (joined.length() + (joined.isEmpty() ? 0 : 2) + next.length() > MAX_LIST_LENGTH) break;
            if (!joined.isEmpty()) joined.append(", ");
            joined.append(next);
        }
        return joined.isEmpty() ? null : joined.toString();
    }
}
