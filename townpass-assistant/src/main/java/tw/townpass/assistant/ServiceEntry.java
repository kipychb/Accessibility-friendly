package tw.townpass.assistant;

import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "official_services")
public class ServiceEntry {
    @Id
    public String id;
    public String title;
    public String mode;
    public String category;
    @ElementCollection(fetch = FetchType.EAGER)
    public List<String> aliases = new ArrayList<>();
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
