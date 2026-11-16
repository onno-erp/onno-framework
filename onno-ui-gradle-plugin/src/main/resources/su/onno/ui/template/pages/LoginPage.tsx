import { DefaultLogin, type LoginPageProps } from "@onno/ui";

/**
 * The login page. It starts as the framework's default: the password form and/or one button per
 * SSO provider the server offers (`methods`). Replace it with anything — it renders before sign-in,
 * so it has no business model; sign in with `useSignIn()`:
 *
 *   signIn.password(username, password)
 *   signIn.challenge("code", { email })        // a Java LoginMethod bean's first step
 *   signIn.method("code", { email, code })     // …and its sign-in
 *   signIn.redirect(methods.providers[0])      // an SSO provider, e.g. Keycloak
 *
 * Keep the branded frame and swap only the card's content:
 *
 *   <LoginLayout error={error}>
 *     <EmailCodeForm />
 *     {methods && <SsoButtons providers={methods.providers} />}
 *   </LoginLayout>
 *
 * Parts: LoginLayout, SignInMethods, PasswordLogin, SsoButtons, LoginButton.
 */
export function LoginPage(props: LoginPageProps) {
  return <DefaultLogin {...props} />;
}
