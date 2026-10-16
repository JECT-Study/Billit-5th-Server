package com.ject

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class BillitApplication

fun main(args: Array<String>) {
    runApplication<BillitApplication>(*args)
}
