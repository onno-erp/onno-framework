## Enterprise connectors (`com.onec.enterprise`, separate repo)

Gated by `onec.guesty.enabled` / `onec.hospedajes.enabled` / `onec.tochka.enabled`. Each reads its
own `onec.<connector>.*` block (base URL, credentials/tokens, timeouts, retry, token cache). See the
[onec-enterprise](https://github.com/onec-erp/onec-enterprise) module READMEs.
