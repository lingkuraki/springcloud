package com.kuraki.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cloud.stream.annotation.Output;
import org.springframework.messaging.MessageChannel;
import org.springframework.stereotype.Component;

public interface MySource {

    @Output("output-1")
    MessageChannel output1();

    @Output("output-2")
    MessageChannel output2();
}

@Component
class OutputSender{

    @Autowired
    @Qualifier("output-1")
    private MessageChannel output;
}
