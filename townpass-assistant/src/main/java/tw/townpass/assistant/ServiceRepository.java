package tw.townpass.assistant;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

interface ServiceRepository extends JpaRepository<ServiceEntry, String> {
    @Query("select distinct s from ServiceEntry s left join fetch s.aliases order by s.id")
    List<ServiceEntry> listWithAliases();
}
