package com.bookstore.orderservice.book;

import java.util.List;

public record BookDto (
    String isbn,
    String title,
    String author,
    String publisher,
    String supplier,
    Long price,
    List<String> photos,
    Integer inventory
) {
}
