package com.hipster.user.event;

import com.hipster.rating.event.RatingEvent;
import com.hipster.user.repository.UserRepository;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class UserActivityConsumerTest {

    @InjectMocks
    private UserActivityConsumer userActivityConsumer;

    @Mock
    private UserRepository userRepository;

    @Mock
    private Channel channel;

    @Test
    @DisplayName("평점 생성/수정 이벤트는 consume 시각이 아니라 eventTs 로 마지막 활동 시간을 갱신한다")
    void consumeUserActivityEvent_UsesEventTimestamp() throws IOException {
        LocalDateTime eventTs = LocalDateTime.of(2026, 3, 18, 14, 30, 0);
        RatingEvent event = new RatingEvent(1L, 10L, 0.0, 4.5, true, false, 0.9, eventTs);

        userActivityConsumer.consumeUserActivityEvent(event, channel, 77L);

        ArgumentCaptor<LocalDateTime> timeCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(userRepository).updateLastActiveDate(org.mockito.ArgumentMatchers.eq(1L), timeCaptor.capture());
        assertThat(timeCaptor.getValue()).isEqualTo(eventTs);
        verify(channel).basicAck(77L, false);
    }

    @Test
    @DisplayName("평점 삭제 이벤트는 마지막 활동 시간을 갱신하지 않는다")
    void consumeUserActivityEvent_DeleteDoesNotUpdateLastActiveDate() throws IOException {
        RatingEvent event = new RatingEvent(1L, 10L, 4.5, 0.0, false, true, 0.9, LocalDateTime.of(2026, 3, 18, 14, 30, 0));

        userActivityConsumer.consumeUserActivityEvent(event, channel, 78L);

        verify(userRepository, never()).updateLastActiveDate(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any());
        verify(channel).basicAck(78L, false);
    }
}
