package ru.tbcarus.photocloudserver.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.LocalDateTime;

@Entity
@Data
@AllArgsConstructor
@NoArgsConstructor
@Table(name = "email_requests")
public class EmailRequest {
    // Вынести в конфиги
    public static final int DEFAULT_EXPIRED_DAYS = 3;
    public static final int ACTIVE_REQUESTS_MAX = 3;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @ManyToOne(fetch = FetchType.EAGER)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private User user; // владелец запроса

    private String code; // код

    @Enumerated(EnumType.STRING)
    private EmailRequestType type; // тип запроса

    @CreationTimestamp
    @Column(name = "create_date", updatable = false)
    private LocalDateTime createDate;

    private boolean used; // была ли использована ссылка

    public EmailRequest(EmailRequestType type) {
        this.type = type;
        this.createDate = LocalDateTime.now();
        this.used = false;
    }

    public boolean isActive() {
        return createDate.plusDays(DEFAULT_EXPIRED_DAYS).isAfter(LocalDateTime.now());
    }

    public boolean isExpired() {
        return !isActive();
    }

}
