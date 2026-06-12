package com.company.observability.alert;

import com.company.observability.domain.SlaBreachEvent;

public interface AlertSender {

    void send(SlaBreachEvent breach) throws AlertDeliveryException;

    String channelName();
}
