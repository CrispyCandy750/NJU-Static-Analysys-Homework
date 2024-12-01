interface Iterator {
    Object next();
}

class TwoObjectTemp {
    public static void main(String[] args) {
        m(); // TwoObject
    }

    static void m() {
        List l1 = new List();  // o11
        l1.add(new Object());  // context: TwoObject, 参数的上下文是o12
        List l2 = new List();  // o13
        l2.add(new Object());   // context: TwoObject, 参数的上下文为o14

        Iterator i1 = l1.iterator();  // TwoObject, i1为
        Object o1 = i1.next();  // TwoObject
        Iterator i2 = l2.iterator();  // TwoObject
        Object o2 = i2.next();  // TwoObject
    }
}

class List {

    Object element;

    void add(Object e) {  // o12, o14
        this.element = e;   // TwoObject的add的this.element = o12, o13
    }

    Iterator iterator() {
        return new ListIterator();
    }

    class ListIterator implements Iterator {

        public Object next() {
            return element;
        }
    }
}
