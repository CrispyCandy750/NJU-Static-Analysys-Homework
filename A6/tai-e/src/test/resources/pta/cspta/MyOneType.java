class MyOneType {
    public static void main(String[] args) {
        new A().m();
        new B().m();
    }
}

class A {
    void m() {
        new C().set(new D());
    }
}

class B {
    void m() {
        new C().set(new D());
    }
}

class C {
    D f;

    void set(D p) {
        this.f = p;
    }
}

class D {
}