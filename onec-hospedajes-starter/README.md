# onec-hospedajes-starter

Client for the Spanish Ministerio del Interior **SES.HOSPEDAJES** "Servicio de Comunicación"
(RD 933/2021 traveler/lodging registration), SOAP WSDL **v3.1.3**.

It submits *partes de viajeros* (tipoComunicacion **PV**), queries batch outcomes, and cancels
comunicaciones, recording the asynchronous lifecycle in an audit ledger.

## What the service expects

The `comunicacion` SOAP operation carries a `cabecera` (arrendador code, application name, operation,
communication type) and a `solicitud`: the inner XML (`altaParteHospedaje` schema) **ZIP-compressed
and Base64-encoded**. Submission is asynchronous — it returns a `numeroLote`; you later call
`consultaLote` to learn, per comunicación (keyed by `orden`), whether it was registered (you get a
`codigoComunicacion`) or rejected (you get a `tipoError`/`error`).

## Getting credentials (one-time, on the Sede Electrónica)

1. Log in to the SES.HOSPEDAJES platform with a digital certificate / Cl@ve:
   <https://hospedajes.ses.mir.es/hospedajes-sede/>
2. **Alta nueva de arrendador** → you receive a *código de arrendador* and register at least one
   establishment (you get a *código de establecimiento*).
3. **Usuario WebService** section → request SOAP credentials. The username is typically your
   NIF/CIF followed by `WS` (e.g. `B12345678WS`); you set the password.

## Configuration

```yaml
onec:
  hospedajes:
    enabled: true
    production: false            # false -> pre-production sandbox (hospedajes.pre-ses.mir.es)
    arrendador: "0000000001"     # código de arrendador
    aplicacion: "onec"           # free-text application name
    username: "B12345678WS"      # WebService user
    password: "********"
    # max-batch-size: 100        # service caps at 100 comunicaciones per request
```

Endpoints are derived from `production`; override with `endpoint` if needed. The pre-production and
production URLs are baked into `HospedajesProperties`.

## TLS / trust store

The MIR endpoint certificate is issued by **FNMT-RCM**, whose CA is **not** in the JVM default
`cacerts` — without trusting it you get `PKIX path building failed`. This starter **bundles the FNMT
CA chain** (`classpath:ses-hospedajes/fnmt-ca.pem`) and trusts it automatically; nothing to do.

To override (e.g. a corporate trust store, or to pin a different cert):

```yaml
onec:
  hospedajes:
    truststore:
      location: "file:/etc/onec/ses-truststore.p12"   # or a .pem/.crt; or type: PEM
      password: "********"
      # type: PKCS12 | JKS | PEM
```

A PEM/CRT location (or `type: PEM`) is read directly — no `keytool` step required. Mutual TLS is
supported via the analogous `keystore.*` block but is not required by the spec.

## Usage

```java
@Autowired HospedajesService hospedajes;

// Submit a parte de viajeros for one establishment
ComunicacionResult r = hospedajes.registrar("EST-001", List.of(comunicacion));
// r.accepted() == true, r.numeroLote() == "LOTE-..."

// Later (e.g. a scheduled job) reconcile outstanding batches
int updated = hospedajes.reconcile(50);
```

The example application (`example/`) wires this to the rentals domain: a `Booking` moving to
`CHECKED_IN` submits its guests as a parte, and a scheduled job reconciles batches.
