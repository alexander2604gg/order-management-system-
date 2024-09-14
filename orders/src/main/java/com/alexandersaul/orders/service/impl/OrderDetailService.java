package com.alexandersaul.orders.service.impl;

import com.alexandersaul.orders.dto.inventory.InventoryResponseDTO;
import com.alexandersaul.orders.dto.order.OrderResponseDTO;
import com.alexandersaul.orders.dto.orderdetail.OrderDetailRequestDTO;
import com.alexandersaul.orders.entity.Order;
import com.alexandersaul.orders.entity.OrderDetail;
import com.alexandersaul.orders.exception.ResourceNotFoundException;
import com.alexandersaul.orders.exception.StockNotEnoughException;
import com.alexandersaul.orders.mapper.OrderDetailMapper;
import com.alexandersaul.orders.repository.OrderDetailRepository;
import com.alexandersaul.orders.repository.OrderRepository;
import com.alexandersaul.orders.service.IOrderDetailService;
import com.alexandersaul.orders.service.client.InventoryFeignClient;
import com.fasterxml.jackson.core.io.BigDecimalParser;
import jakarta.persistence.EntityNotFoundException;
import org.hibernate.StaleObjectStateException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class OrderDetailService implements IOrderDetailService {
    @Autowired
    private OrderDetailRepository orderDetailRepository;
    @Autowired
    private OrderDetailMapper orderDetailMapper;
    @Autowired
    private OrderService orderService;
    @Autowired
    private InventoryFeignClient inventoryFeignClient;

    @Override
    public void createOrderDetails(List<OrderDetailRequestDTO> orderDetailRequestDTOList) {

        if (orderDetailRequestDTOList == null || orderDetailRequestDTOList.isEmpty()) {
            throw new IllegalArgumentException("Detail order list can not be null.");
        }

        List<OrderDetail> orderDetailList = orderDetailMapper.toEntityList(orderDetailRequestDTOList);
        Long orderId = orderDetailList.get(0).getOrder().getOrderId();

        Map<Long, Long> listProductsIdsWithQuantity = new HashMap<>();

        for (OrderDetail detail : orderDetailList) {
            if (detail.getOrder() == null || !orderId.equals(detail.getOrder().getOrderId())) {
                throw new IllegalArgumentException("All the details should have the same order id.");
            }
            listProductsIdsWithQuantity.put(detail.getProductId(), (long) detail.getQuantity());
        }

        ResponseEntity<List<InventoryResponseDTO>> inventoryJson =
                inventoryFeignClient.findInventoryByProductIds(new ArrayList<>(listProductsIdsWithQuantity.keySet()));

        List<InventoryResponseDTO> inventoryResponseList = inventoryJson.getBody();

        if (inventoryResponseList == null || inventoryResponseList.isEmpty()) {
            throw new RuntimeException("Inventory service returned no data.");
        }

        for (InventoryResponseDTO inventory : inventoryResponseList) {
            Long requestedQuantity = listProductsIdsWithQuantity.get(inventory.getProductId());

            if (requestedQuantity > inventory.getQuantity()) {
                throw new StockNotEnoughException(inventory.getProductId(), inventory.getQuantity());
            }
        }

        OrderResponseDTO orderResponseDTO = orderService.findById(orderId);

        if (orderResponseDTO != null) {
            orderDetailList.forEach(orderDetail -> {
                orderDetail.setCreatedAt(LocalDateTime.now());
                orderDetail.setCreatedBy("Alexander");
            });
            orderDetailRepository.saveAll(orderDetailList);
            BigDecimal totalAmount = calculateTotalAmount(orderDetailList);
            orderService.updateTotalAmount(orderId, totalAmount);

        }
    }



    @Override
    public boolean deleteOrderDetail(Long id) {
        OrderDetail orderDetail = orderDetailRepository.findById(id).orElseThrow(
                () -> new ResourceNotFoundException("OrderDetail" , "orderId" , String.valueOf(id))
        );
        orderDetailRepository.deleteById(orderDetail.getOrderDetailId());
        return true;
    }


    public BigDecimal calculateTotalAmount (List<OrderDetail> orderDetailList) {
        return orderDetailList.stream()
                .map(orderDetail -> orderDetail.getPricePerUnit().multiply(
                        new BigDecimal(orderDetail.getQuantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

}
