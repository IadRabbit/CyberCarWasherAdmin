package com.meethack.romhack.camp2026;

import java.util.Calendar;

public class Customer {
    private final String name;
    private final String surname;
    private final Calendar creationDate;
    private final long amount;

    public Customer(String name, String surname, Calendar creationDate, long amount) {
        this.name = name;
        this.surname = surname;
        this.creationDate = creationDate;
        this.amount = amount;
    }

    public String getName(){
        return this.name;
    }

    public String getSurname(){
        return this.surname;
    }
    public Calendar getCreationDate(){
        return this.creationDate;
    }

    public long getAmount(){
        return this.amount;
    }
}
