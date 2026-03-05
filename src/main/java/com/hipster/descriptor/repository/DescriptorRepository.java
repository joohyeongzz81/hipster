package com.hipster.descriptor.repository;

import com.hipster.descriptor.domain.Descriptor;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DescriptorRepository extends JpaRepository<Descriptor, Long> {
}
