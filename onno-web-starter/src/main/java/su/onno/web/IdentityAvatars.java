package su.onno.web;

import su.onno.metadata.AttributeDescriptor;
import su.onno.metadata.CatalogDescriptor;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Which attribute of the identity catalog holds a person's photo URL. Convention over
 * configuration: a {@code String} attribute named like a photo — {@code avatar}, {@code avatarUrl},
 * {@code photo}, {@code photoUrl}, {@code picture}, {@code image}, {@code imageUrl} (first match in
 * that order) — is the avatar. No such attribute, no avatar.
 */
public final class IdentityAvatars {

    private static final List<String> NAMES = List.of(
            "avatar", "avatarurl", "photo", "photourl", "picture", "pictureurl", "image", "imageurl");

    private IdentityAvatars() {}

    public static Optional<AttributeDescriptor> attribute(CatalogDescriptor catalog) {
        if (catalog == null) {
            return Optional.empty();
        }
        for (String name : NAMES) {
            for (AttributeDescriptor a : catalog.attributes()) {
                if (a.fieldName().toLowerCase(Locale.ROOT).equals(name) && isText(a)) {
                    return Optional.of(a);
                }
            }
        }
        return Optional.empty();
    }

    private static boolean isText(AttributeDescriptor a) {
        return a.javaType() == String.class;
    }
}
