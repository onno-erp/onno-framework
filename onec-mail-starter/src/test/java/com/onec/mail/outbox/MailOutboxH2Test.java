package com.onec.mail.outbox;

import org.jdbi.v3.core.Jdbi;

import java.util.UUID;

/** {@link MailOutboxContract} on H2 — exercises the portable (non-Postgres) SQL paths. */
class MailOutboxH2Test extends MailOutboxContract {

    @Override
    protected Jdbi createJdbi() {
        return Jdbi.create("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
    }
}
