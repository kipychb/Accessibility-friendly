package tw.townpass.assistant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "official_services")
public class ServiceEntry {
    @Id
    public String id;
    public String title;
    public String mode;
    public String authority;
    @Column(length = 1024)
    public String officialUrl;
    @Column(length = 1024)
    public String sourceUrl;
    @Column(length = 1024)
    public String scopeEvidenceUrl;
    @Column(length = 1024)
    public String summary;
}
