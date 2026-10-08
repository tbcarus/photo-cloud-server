package ru.tbcarus.photocloudserver.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.springframework.beans.factory.annotation.Value;
import ru.tbcarus.photocloudserver.util.ConfigUtil;

import java.time.LocalDateTime;

@Entity
@Data
@AllArgsConstructor
@NoArgsConstructor
@Table(name = "email_requests")
@Builder
public class EmailRequest {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String code; // код

    @Enumerated(EnumType.STRING)
    private EmailRequestType type; // тип запроса

    private boolean used; // была ли использована ссылка

    @ManyToOne(fetch = FetchType.LAZY)
    @OnDelete(action = OnDeleteAction.CASCADE)
    @JoinColumn(name = "user_id")
    private User user; // владелец запроса

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    public boolean isActive() {
        return createdAt.plusDays(ConfigUtil.DEFAULT_EXPIRED_DAYS).isAfter(LocalDateTime.now());
    }

    public boolean isExpired() {
        return !isActive();
    }

}
