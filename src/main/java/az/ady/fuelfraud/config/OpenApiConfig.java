package az.ady.fuelfraud.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI fuelFraudOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("ms-fuelfraud API")
                        .description("Detects fuel theft and refuelling events from raw fuel-sensor "
                                + "measurements uploaded as Excel files.")
                        .version("v1")
                        .contact(new Contact().name("Taleh").email("ftaleh96@gmail.com"))
                        .license(new License().name("Proprietary")));
    }
}
