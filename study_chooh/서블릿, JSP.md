### 서블릿으로 회원 관리 웹 애플리케이션 만들기

#### 서블릿으로 회원 등록 HTML 폼 제공

```java
@Override
protected void service(HttpServletRequest request, HttpServletResponse 
response) throws ServletException, IOException {
response.setContentType("text/html");
response.setCharacterEncoding("utf-8");
PrintWriter w = response.getWriter();
w.write("<!DOCTYPE html>\n" +
 "<html>\n" +
 "<head>\n" +
 "    <meta charset=\"UTF-8\">\n" +
 "    <title>Title</title>\n" +
 "</head>\n" +
 "<body>\n" +
 "<form action=\"/servlet/members/save\" method=\"post\">\n" +
 "    username: <input type=\"text\" name=\"username\" />\n" +
 "    age:      <input type=\"text\" name=\"age\" />\n" +
 "    <button type=\"submit\">전송</button>\n" +
 "</form>\n" +
 "</body>\n" +
 "</html>\n");
 }
```

자바 코드로 HTML을 제공해야 하므로 쉽지 않은 작업이다.

#### 서블릿으로 회원 저장

```java
@Override
protected void service(HttpServletRequest request, HttpServletResponse 
response) throws ServletException, IOException {
System.out.println("MemberSaveServlet.service");
String username = request.getParameter("username");
int age = Integer.parseInt(request.getParameter("age"));
Member member = new Member(username, age);
System.out.println("member = " + member);
memberRepository.save(member);
response.setContentType("text/html");
response.setCharacterEncoding("utf-8");
PrintWriter w = response.getWriter();
        w.write("<html>\n" +
 "<head>\n" +
 "    <meta charset=\"UTF-8\">\n" +
 "</head>\n" +
 "<body>\n" +
 "성공\n" +
 "<ul>\n" +
 "    <li>id="+member.getId()+"</li>\n" +
 "    <li>username="+member.getUsername()+"</li>\n" +
 "    <li>age="+member.getAge()+"</li>\n" +
 "</ul>\n" +
 "<a href=\"/index.html\">메인</a>\n" +
 "</body>\n" +
 "</html>");
 }
```

이렇게 자바 코드로 HTML을 만들어 내는 것은 복잡하고 비효율적이다. HTML문서에 동적으로 변경해야 하는 부분만 자바 코드를 넣을 수 있다면 더 편리할 것이다.

이것이 바로 템플릿 엔진이 나온 이유다. 템플릿 엔진을 사용하면 HTML 문서에서 필요한 곳만 코드를 적용해서 동적으로 변경할 수 있다.

### JSP로 회원 관리 웹 애플리케이션 만들기

#### 회원 저장 JSP

```jsp
 <%@ page import="hello.servlet.domain.member.MemberRepository" %>
 <%@ page import="hello.servlet.domain.member.Member" %>
 <%@ page contentType="text/html;charset=UTF-8" language="java" %>
 <%
 //    request, response 사용 가능
    MemberRepository memberRepository = MemberRepository.getInstance();
    System.out.println("save.jsp");
    String username = request.getParameter("username");
    int age = Integer.parseInt(request.getParameter("age"));
    Member member = new Member(username, age);
    System.out.println("member = " + member);
    memberRepository.save(member);
 %>
 <html>
 <head>
    <meta charset="UTF-8">
 </head>
 <body>
성공
<ul>
    <li>id=<%=member.getId()%></li>
    <li>username=<%=member.getUsername()%></li>
    <li>age=<%=member.getAge()%></li>
 </ul>
 <a href="/index.html">메인</a>
 </body>
 </html>
```

JSP는 서버 내부에서 서블릿으로 변환된다.

HTML을 중심으로 하고, 자바 코드를 부분부분 입력해주었다.

하지만 이렇게 하면 코드의 상위 절반은 비즈니스 로직이고, 나머지 하위 절반만 뷰 영역이다.

JSP가 너무 많은 역할을 한다.

유지보수가 어려워진다.

#### MVC 패턴의 등장

비즈니스 로직은 서블릿처럼 다른곳에서 처리하고, JSP는 뷰를 그리는 일에만 집중하도록 하는 MVC 패턴이 등장했다.
