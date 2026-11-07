package tw.townpass.assistant;

import org.springframework.data.jpa.repository.JpaRepository;

interface ServiceRepository extends JpaRepository<ServiceEntry, String> {}
