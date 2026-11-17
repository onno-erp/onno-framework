/**
 * Field names treated as a person's photo URL (mirrors the server's {@code IdentityAvatars}
 * convention): the first one present on a record is its avatar.
 */
export const IDENTITY_AVATAR_FIELDS = [
  "avatar", "avatarUrl", "photo", "photoUrl", "picture", "pictureUrl", "image", "imageUrl",
] as const;
