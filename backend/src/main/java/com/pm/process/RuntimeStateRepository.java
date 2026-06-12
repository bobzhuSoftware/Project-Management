package com.pm.process;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RuntimeStateRepository extends JpaRepository<RuntimeStateEntity, String> {
}
