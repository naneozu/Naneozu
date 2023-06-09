import 'package:flutter/material.dart';
import 'package:nnz/src/config/config.dart';
import 'package:nnz/src/model/hash_tag_model.dart';
import 'package:nnz/src/pages/search/tag_page.dart';

class HashTag extends StatefulWidget {
  final List<HashTagModel> items;

  const HashTag({Key? key, required this.items}) : super(key: key);

  @override
  _HashTagState createState() => _HashTagState();
}

class _HashTagState extends State<HashTag> {
  int selectedIndex = -1;

  @override
  Widget build(BuildContext context) {
    return SingleChildScrollView(
      child: Center(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Container(
              margin: const EdgeInsets.only(
                  left: 18, right: 12, top: 10, bottom: 10),
              decoration: BoxDecoration(
                borderRadius: BorderRadius.circular(20),
              ),
              child: Wrap(
                spacing: 8,
                runSpacing: 10,
                children: List.generate(
                  widget.items.length,
                  (index) => GestureDetector(
                    onTap: () {
                      setState(() {
                        selectedIndex = index;
                      });

                      Navigator.push(
                        context,
                        MaterialPageRoute(
                          builder: (context) => TagPage(
                            tagName: widget.items[index].tag,
                          ),
                        ),
                      );
                    },
                    child: AnimatedContainer(
                      duration: Duration(milliseconds: 200),
                      padding: const EdgeInsets.all(8),
                      decoration: BoxDecoration(
                        boxShadow: [
                          BoxShadow(
                            color: Colors.grey.withOpacity(0.7),
                            spreadRadius: 1,
                            blurRadius: 3,
                            offset: Offset(0, 5),
                          ),
                        ],
                        color: selectedIndex == index
                            ? Color.fromARGB(
                                255, 255, 232, 124) // Selected color
                            : Color.fromARGB(
                                255, 255, 232, 124), // Default color
                        borderRadius: BorderRadius.circular(20),
                      ),
                      child: Text(
                        widget.items[index].tag,
                        style: TextStyle(
                          color: Colors.black87,
                          fontSize: 12,
                          fontWeight: FontWeight.bold,
                        ),
                      ),
                    ),
                  ),
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}
