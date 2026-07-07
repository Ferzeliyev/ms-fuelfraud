package az.ady.fuelfraud;

import az.ady.fuelfraud.config.FuelFraudProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(FuelFraudProperties.class)
public class MsFuelfraudApplication {

    public static void main(String[] args) {
        SpringApplication.run(MsFuelfraudApplication.class, args);
    }
}
