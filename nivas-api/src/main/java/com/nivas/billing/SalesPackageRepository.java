package com.nivas.billing;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.nivas.billing.domain.PackageType;
import com.nivas.billing.domain.SalesPackage;

public interface SalesPackageRepository extends JpaRepository<SalesPackage, UUID> {

    Optional<SalesPackage> findByCodeAndActiveTrue(String code);

    List<SalesPackage> findByActiveTrueOrderBySortOrderAsc();

    List<SalesPackage> findByPackageTypeAndActiveTrueOrderBySortOrderAsc(PackageType packageType);
}
