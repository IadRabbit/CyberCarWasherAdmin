package com.meethack.romhack.camp2026.assets;

import java.util.Calendar;

public class Customer {
    private final String name;
    private final String surname;
    private final Calendar creationDate;
    private final int amount;

    public Customer(String name, String surname, Calendar creationDate, int amount) {
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

    public int getAmount(){
        return this.amount;
    }
}
