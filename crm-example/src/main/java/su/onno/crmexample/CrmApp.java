package su.onno.crmexample;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** A standalone CRM consumer application built from the projects in this repository. */
@SpringBootApplication
public class CrmApp {

    public static void main(String[] args) {
        SpringApplication.run(CrmApp.class, args);
    }
}
