package br.com.itau.challenge.balance

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

@Configuration
class ClockConfig {

    /**
     * O relógio como dependência injetada, e não `Instant.now()` espalhado pelo código.
     *
     * É o que permite testar a rejeição de eventos vindos do futuro sem esperar o tempo passar
     * nem depender do relógio da máquina que roda a suíte: o teste fixa um instante e verifica a
     * fronteira exata.
     */
    @Bean
    fun clock(): Clock = Clock.systemUTC()
}
