package com.ject.module.inventory.infra.persistence.jpa

import com.ject.module.inventory.domain.Reservation
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface ReservationJpaRepository : JpaRepository<Reservation, UUID>
