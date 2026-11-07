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
    /** 是否為台北通（正式版或比賽版 TownPass）實際提供的服務；false 的項目一律回答「台北通沒有提供」。 */
    public boolean inTaipeiPass = true;
    /** inTaipeiPass 為 false 的原因（依官方資料查證的結果）。 */
    @Column(length = 1024)
    public String scopeNote;
}
