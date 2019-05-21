package com.kuraki.controller;


import com.kuraki.entity.User;
import com.kuraki.service.HelloService;
import com.kuraki.service.RefactorHelloService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CustomerController {

    @Autowired
    private HelloService helloService;

    @Autowired
    private RefactorHelloService refactorHelloService;

    @RequestMapping(value = "/feign-consumer", method = RequestMethod.GET)
    public String helloConsumer() {
        return helloService.hello();
    }

    @RequestMapping(value = "/feign-consumer2", method = RequestMethod.GET)
    public String helloConsumer2() {
        StringBuilder sb = new StringBuilder();
        sb.append(helloService.hello()).append("\n");
        sb.append(helloService.hello("kuraki")).append("\n");
        sb.append(helloService.hello("kuraki", 26)).append("\n");
        sb.append(helloService.hello(new User("kuraki", 26))).append("\n");
        return sb.toString();
    }

    @RequestMapping(value = "/feign-consumer3", method = RequestMethod.GET)
    public String helloConsumer3() {
        StringBuilder sb = new StringBuilder();
        sb.append(refactorHelloService.hello("kuraki")).append("\n");
        sb.append(refactorHelloService.hello("kuraki", 26)).append("\n");
        sb.append(refactorHelloService.hello(new com.kuraki.feign.dto.User("kuraki", 26))).append("\n");
        return sb.toString();
    }

}
