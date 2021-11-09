package uwdb.discovery.dependency.approximate.common;

import org.apache.commons.lang3.builder.HashCodeBuilder;


public class AttributePair {

    public Integer AttX;
    public Integer AttY;

    public AttributePair(int X, int Y) {
        if (X >= Y) {
            this.AttX = X;
            this.AttY = Y;
        } else {
            this.AttX = Y;
            this.AttY = X;
        }
    }

    //overrides
    @Override
    public int hashCode() {
        HashCodeBuilder builder = new HashCodeBuilder();
        builder.append(AttX.hashCode());
        builder.append(AttY.hashCode());
        return builder.toHashCode();
    }

    @Override
    public boolean equals(Object obj) {
        AttributePair other = (AttributePair) obj;
        boolean b1 = (other.AttX.equals(AttX) && other.AttY.equals(AttY));
        boolean b2 = (other.AttX.equals(AttY) && other.AttY.equals(AttX));
        return (b1 || b2);
    }

    @Override
    public String toString() {
        return "<" + AttX + "," + AttY + '>';
    }
}
