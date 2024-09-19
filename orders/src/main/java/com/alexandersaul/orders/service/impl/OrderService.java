package com.alexandersaul.orders.service.impl;

import com.alexandersaul.orders.constants.OrderStatus;
import com.alexandersaul.orders.dto.order.OrderRequestDTO;
import com.alexandersaul.orders.dto.order.OrderResponseDTO;
import com.alexandersaul.orders.dto.order.UpdateOrderStatusDTO;
import com.alexandersaul.orders.dto.orderdetail.OrderDetailRequestDTO;
import com.alexandersaul.orders.entity.Order;
import com.alexandersaul.orders.entity.OrderDetail;
import com.alexandersaul.orders.exception.*;
import com.alexandersaul.orders.mapper.OrderMapper;
import com.alexandersaul.orders.repository.OrderRepository;
import com.alexandersaul.orders.service.IOrderService;
import jakarta.transaction.Transactional;
import org.aspectj.weaver.ast.Or;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
public class OrderService implements IOrderService {

    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private OrderMapper orderMapper;
    @Autowired
    private OrderDetailService orderDetailService;

    @Override
    public void createOrder(OrderRequestDTO orderRequestDTO) {
        Order order = orderMapper.toEntity(orderRequestDTO);
        order.setCreatedBy("Alexander Saul");
        order.setCreatedAt(LocalDateTime.now());
        order.setStatus("PENDING");
        orderRepository.save(order);
    }

    @Override
    public OrderResponseDTO findById(Long id) {
        Optional<Order> orderOptional  = orderRepository.findById(id);
        if (orderOptional.isEmpty()){
            throw new ResourceNotFoundException("Order","orderId",String.valueOf(id));
        }
        return orderMapper.toDTO(orderOptional.get());
    }


    @Override
    @Transactional
    public void addOrderDetail(Long orderId, List<OrderDetailRequestDTO> orderDetailRequestDTOList) {
        Order order = orderRepository.findById(orderId).orElseThrow(
                () -> new ResourceNotFoundException("Order" , "orderId" , orderId.toString())
        );
        orderDetailService.createOrderDetails(order , orderDetailRequestDTOList);
        updateTotalAmount(order , calculateTotalAmount(order));

    }

    @Override
    @Transactional
    public void updateOrderStatus(Long id, UpdateOrderStatusDTO orderUpdateStatusDTO) {
        Order order = orderRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Order", "OrderId", String.valueOf(id)));

        OrderStatus currentStatus = OrderStatus.valueOf(order.getStatus());
        OrderStatus newStatus = OrderStatus.valueOf(orderUpdateStatusDTO.getStatus());

        if (OrderStatus.CANCELED.equals(currentStatus) || OrderStatus.PAID.equals(currentStatus)) {
            throw new OrderNotValidToProcessPaymentException(currentStatus.name());
        }

        if (OrderStatus.PENDING.equals(currentStatus) && OrderStatus.PAID.equals(newStatus)) {
            try {
                boolean isPaid = paidOrder(order);
                if (isPaid) {
                    order.setStatus(newStatus.name());
                    order.setUpdatedAt(LocalDateTime.now());
                    orderRepository.save(order);
                    return;
                }
            } catch (Exception e) {
                throw new PaymentProcessFailedException(e);
            }
        }

        throw new NotValidArgumentForPaymentException(newStatus.name());
    }


    @Override
    @Transactional
    public void deleteOrder(Long id) {
        Order order = orderRepository.findById(id).orElseThrow(
                () -> new ResourceNotFoundException("Order" , "orderId" , String.valueOf(id))
        );
        if (OrderStatus.CANCELED.name().equals(order.getStatus())){
            orderRepository.delete(order);
        } else {
            throw new OrderNotAllowedToDeleteException(order.getStatus());
        }
    }

    public void updateTotalAmount(Order order, BigDecimal totalAmount) {
        order.setTotalAmount(totalAmount);
        orderRepository.save(order);
    }

    public boolean paidOrder(Order order) {
        return orderDetailService.processOrderPayment(order.getDetails());
    }

    public BigDecimal calculateTotalAmount (Order order) {
        return order.getDetails().stream()
                .map(orderDetail -> orderDetail.getPricePerUnit().multiply(
                        new BigDecimal(orderDetail.getQuantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

}
