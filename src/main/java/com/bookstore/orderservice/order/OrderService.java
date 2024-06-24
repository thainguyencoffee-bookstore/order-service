package com.bookstore.orderservice.order;

import com.bookstore.orderservice.book.BookClient;
import com.bookstore.orderservice.book.BookDto;
import com.bookstore.orderservice.book.BookNotFoundException;
import com.bookstore.orderservice.book.InsufficientStockException;
import com.bookstore.orderservice.order.event.OrderAcceptedMessage;
import com.bookstore.orderservice.order.event.OrderDispatchedMessage;
import com.bookstore.orderservice.order.dto.LineItemRequest;
import com.bookstore.orderservice.order.dto.UserInformation;
import lombok.SneakyThrows;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;

import java.util.List;
import java.util.UUID;

@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);
    private final LineItemRepository lineItemRepository;
    private final OrderRepository orderRepository;
    private final BookClient bookClient;
    private final StreamBridge streamBridge;

    public OrderService(OrderRepository orderRepository,
                        LineItemRepository lineItemRepository,
                        BookClient bookClient,
                        StreamBridge streamBridge) {
        this.lineItemRepository = lineItemRepository;
        this.orderRepository = orderRepository;
        this.bookClient = bookClient;
        this.streamBridge = streamBridge;
    }


    @SneakyThrows
    @Transactional
    public Order submitOrder(List<LineItemRequest> lineItems, UserInformation userInformation) {
        var order = new Order();
        order.setStatus(OrderStatus.WAITING_FOR_PAYMENT);
        order.setUserInformation(userInformation);
        orderRepository.save(order);

        // process line item
        double totalPrice = 0.0;
        for (LineItemRequest lineItemRequest : lineItems) {
            totalPrice += processLineItem(lineItemRequest, order);
        }
        order.setTotalPrice(totalPrice);
        orderRepository.save(order);
        // end process line item
        return order;
    }

    public Order buildAcceptedOrder(UUID orderId) {
        return orderRepository.findById(orderId)
                .map(order -> {
                    order.setStatus(OrderStatus.ACCEPTED);
                    orderRepository.save(order);
                    // reduce inventory
                    reduceInventory(lineItemRepository.findAllByOrderId(orderId));
                    // end reduce inventory
                    return order;
                })
                .map(order -> {
                    publishOrderAcceptedEvent(order);
                    return order;
                })
                .orElseThrow(() -> new OrderNotFoundException(orderId));
    }


    public Order consumeOrderDispatchedEvent(OrderDispatchedMessage orderDispatchedMessage) {
        return orderRepository.findById(orderDispatchedMessage.orderId()).map(order -> {
            order.setStatus(OrderStatus.DISPATCHED);
            orderRepository.save(order);
            return order;
        }).orElseThrow(() -> new OrderNotFoundException(orderDispatchedMessage.orderId()));
    }


    private double processLineItem(LineItemRequest lineItemRequest, Order order)
            throws BookNotFoundException, InsufficientStockException {
        var isbn = lineItemRequest.getIsbn();
        var quantity = lineItemRequest.getQuantity();
        try {
            ResponseEntity<BookDto> bookDtoResponse = bookClient.getBookByIsbn(isbn);
            BookDto bookDto = bookDtoResponse.getBody();
            if (bookDto.inventory() < quantity) {
                throw new InsufficientStockException("Book with isbn " + isbn + " has insufficient stock");
            }

            LineItem lineItem = new LineItem();
            lineItem.setQuantity(quantity);
            lineItem.setOrderId(order.getId());
            lineItem.setBookDto(bookDto);
            lineItemRepository.save(lineItem);

            return lineItem.getBookDto().price() * lineItem.getQuantity();
        } catch (HttpClientErrorException.NotFound ex) {
            log.warn("Book with isbn {} not found", isbn);
            throw new BookNotFoundException("Book with isbn " + isbn + " not found");
        } catch (HttpServerErrorException ex) {
            log.error("Server error when calling book service", ex);
            throw new RuntimeException("Book service has errors when calling book service", ex);
        }
    }

    private void reduceInventory(List<LineItem> lineItems) {
        for (LineItem lineItem : lineItems) {
            bookClient.reduceInventoryByIsbn(lineItem.getBookDto().isbn(), lineItem.getQuantity());
        }
    }

    private void publishOrderAcceptedEvent(Order order) {
        if (order.getStatus().equals(OrderStatus.REJECTED)) {
            return;
        }
        List<LineItem> lineItems = lineItemRepository.findAllByOrderId(order.getId());
        OrderAcceptedMessage orderAcceptedMessage = new OrderAcceptedMessage(order.getId(), lineItems, order.getUserInformation());
        var result = streamBridge.send("acceptOrder-out-0", orderAcceptedMessage);
        log.info("Result of sending data for order with id {}: {}", order.getId(), result);
    }

}