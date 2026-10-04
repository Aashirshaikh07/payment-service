package com.aashir.payment.repository;

import com.aashir.payment.entity.ProcessedEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProcessedEvnetRepository extends JpaRepository<ProcessedEvent, String> {

}
